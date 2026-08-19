package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@Service
public class PaymentConfigResolver {


    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;
    private final PrivateStorageFileService privateStorageFileService;
    private final PaymentConfigSnapshotStore snapshotStore;

    public PaymentConfigResolver(
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher,
            PrivateStorageFileService privateStorageFileService,
            PaymentConfigSnapshotStore snapshotStore
    ) {
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
        this.privateStorageFileService = privateStorageFileService;
        this.snapshotStore = snapshotStore;
    }

    public ResolvedPaymentConfig resolve() {
        return resolveDb();
    }

    /**
     * Returns empty only when no enabled database configuration exists. Once a candidate exists,
     * damaged ciphertext and incomplete secret material still fail closed.
     */
    public Optional<ResolvedPaymentConfig> resolveAvailable() {
        return hasEnabledDbConfig() ? Optional.of(resolveDb()) : Optional.empty();
    }

    /**
     * Resolves the configuration persisted on a payment order. Historical database configurations
     * remain usable after they are no longer the enabled/current configuration. A null id is only
     * valid for historical environment snapshots and must be resolved with its fingerprint.
     */
    public ResolvedPaymentConfig resolveForPaymentConfigId(Long paymentConfigId) {
        if (paymentConfigId == null) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        PaymentConfigRow row = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled, secret_cipher_version, secret_key_id
                        from payment_config
                        where id = :paymentConfigId
                        """)
                .param("paymentConfigId", paymentConfigId)
                .query(this::mapPaymentConfigRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return resolveDbRow(row);
    }

    /**
     * Resolves the immutable configuration identity captured by a payment order. Database-backed
     * legacy orders remain safe because their referenced row cannot be mutated. Historical ENV
     * orders are restored only from their encrypted snapshots; live ENV payment configuration is
     * no longer supported. Upgrade-era ENV rows without a fingerprint still fail closed.
     */
    public ResolvedPaymentConfig resolveForPayment(
            Long paymentConfigId,
            String expectedFingerprint
    ) {
        if (!StringUtils.hasText(expectedFingerprint)) {
            if (paymentConfigId == null) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
            }
            return resolveForPaymentConfigId(paymentConfigId);
        }
        String normalizedFingerprint = expectedFingerprint.trim();
        if (paymentConfigId != null) {
            return requireFingerprint(
                    resolveForPaymentConfigId(paymentConfigId),
                    normalizedFingerprint
            );
        }

        try {
            ResolvedPaymentConfig snapshot = snapshotStore.findHistoricalSnapshot(normalizedFingerprint)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
            return requireFingerprint(snapshot, normalizedFingerprint);
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
    }

    /**
     * New payment orders may only capture an enabled database configuration. Historical ENV
     * snapshots remain readable but cannot be created through the runtime path.
     */
    public String captureForPayment(ResolvedPaymentConfig config) {
        if (config == null || config.source() != PaymentConfigSource.DB || config.configId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        return fingerprint(config);
    }

    /**
     * Linearizes creation of a DB-backed payment against administrator configuration updates.
     * Secret material was already read outside the transaction; under the row lock we compare the
     * provider-significant database fields and either the encrypted material or immutable legacy
     * file ids with that resolved object. The
     * payment insert then runs in the same transaction, so a concurrent update either happens
     * first and forces a retry, or waits and is rejected after the configuration becomes referenced.
     */
    public ResolvedPaymentConfig lockForPaymentCreation(ResolvedPaymentConfig config) {
        if (config == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (config.source() != PaymentConfigSource.DB || config.configId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }

        PaymentConfigRow locked = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled, secret_cipher_version, secret_key_id
                        from payment_config
                        where id = :paymentConfigId
                          and status = 'ACTIVE'
                        for update
                        """)
                .param("paymentConfigId", config.configId())
                .query(this::mapPaymentConfigRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
        boolean unchanged;
        try {
            unchanged = locked.enabled()
                    && Objects.equals(locked.id(), config.configId())
                    && Objects.equals(locked.appId(), config.appId())
                    && Objects.equals(locked.mchId(), config.mchId())
                    && Objects.equals(locked.merchantSerialNo(), config.merchantSerialNo())
                    && fingerprintsEqual(decryptApiV3Key(locked), config.apiV3Key())
                    && privateKeyMatches(locked, config)
                    && locked.verifyMode() == config.verifyMode()
                    && Objects.equals(locked.wechatPublicKeyId(), config.wechatPublicKeyId())
                    && publicKeyMatches(locked, config)
                    && Objects.equals(locked.notifyUrl(), config.notifyUrl())
                    && Objects.equals(locked.refundNotifyUrl(), config.refundNotifyUrl());
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        if (!unchanged) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        return config;
    }

    /**
     * A non-reversible identity for all provider-significant settings. It intentionally includes
     * hashes of the key material so same-merchant secret rotation cannot silently reuse an old
     * payment order.
     */
    public String fingerprint(ResolvedPaymentConfig config) {
        if (config == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return sha256(String.join("|",
                config.source() == null ? "" : config.source().name(),
                config.configId() == null ? "" : config.configId().toString(),
                nullToEmpty(config.appId()),
                nullToEmpty(config.mchId()),
                nullToEmpty(config.merchantSerialNo()),
                sha256(nullToEmpty(config.apiV3Key())),
                sha256(nullToEmpty(config.privateKeyPem())),
                config.verifyMode() == null ? "" : config.verifyMode().name(),
                nullToEmpty(config.wechatPublicKeyId()),
                sha256(nullToEmpty(config.wechatPublicKeyPem())),
                nullToEmpty(config.notifyUrl()),
                nullToEmpty(config.refundNotifyUrl())
        ));
    }

    private ResolvedPaymentConfig requireFingerprint(
            ResolvedPaymentConfig config,
            String expectedFingerprint
    ) {
        if (!fingerprintsEqual(expectedFingerprint, fingerprint(config))) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        return config;
    }

    private boolean fingerprintsEqual(String expectedFingerprint, String actualFingerprint) {
        byte[] expected = nullToEmpty(expectedFingerprint).getBytes(StandardCharsets.UTF_8);
        byte[] actual = nullToEmpty(actualFingerprint).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public ResolvedPaymentConfig resolveDb() {
        PaymentConfigRow row = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled, secret_cipher_version, secret_key_id
                        from payment_config
                        where enabled = true
                          and status = 'ACTIVE'
                        order by id desc
                        limit 1
                        """)
                .query(this::mapPaymentConfigRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return resolveDbRow(row);
    }

    private boolean hasEnabledDbConfig() {
        Long count = jdbcClient.sql("""
                        select count(*)
                        from payment_config
                        where enabled = true and status = 'ACTIVE'
                        """)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    private ResolvedPaymentConfig resolveDbRow(PaymentConfigRow row) {
        PaymentVerifyMode verifyMode = requireSupportedVerifyMode(row.verifyMode());

        return new ResolvedPaymentConfig(
                PaymentConfigSource.DB,
                row.id(),
                row.configName(),
                row.enabled(),
                row.appId(),
                row.mchId(),
                row.merchantSerialNo(),
                decryptApiV3Key(row),
                readPrivateKey(row),
                row.notifyUrl(),
                row.refundNotifyUrl(),
                verifyMode,
                requiredPublicKeyId(verifyMode, row.wechatPublicKeyId()),
                readPublicKey(verifyMode, row),
                row.privateKeyFileId(),
                row.merchantCertificateFileId(),
                row.wechatPublicKeyFileId()
        );
    }

    private String readPrivateFile(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return privateStorageFileService.readSecretText(fileId);
    }

    private String readPrivateKey(PaymentConfigRow row) {
        if (StringUtils.hasText(row.privateKeyPemCiphertext())) {
            return decryptMaterial(privateKeyPemContext(row.id()), row.privateKeyPemCiphertext(), row);
        }
        return readPrivateFile(row.privateKeyFileId());
    }

    private String readOptionalPrivateFile(Long fileId) {
        if (fileId == null) {
            return "";
        }
        return privateStorageFileService.readSecretText(fileId);
    }

    private String requiredPublicKeyId(PaymentVerifyMode verifyMode, String publicKeyId) {
        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY && !StringUtils.hasText(publicKeyId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return nullToEmpty(publicKeyId);
    }

    private String readPublicKey(PaymentVerifyMode verifyMode, PaymentConfigRow row) {
        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY) {
            if (StringUtils.hasText(row.wechatPublicKeyPemCiphertext())) {
                return decryptMaterial(
                        wechatPublicKeyPemContext(row.id()), row.wechatPublicKeyPemCiphertext(), row);
            }
            return readPrivateFile(row.wechatPublicKeyFileId());
        }
        return readOptionalPrivateFile(row.wechatPublicKeyFileId());
    }

    private PaymentVerifyMode normalizeVerifyMode(PaymentVerifyMode verifyMode) {
        return verifyMode == null ? PaymentVerifyMode.PUBLIC_KEY : verifyMode;
    }

    private PaymentVerifyMode requireSupportedVerifyMode(PaymentVerifyMode verifyMode) {
        PaymentVerifyMode normalized = normalizeVerifyMode(verifyMode);
        if (normalized != PaymentVerifyMode.PUBLIC_KEY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private PaymentConfigRow mapPaymentConfigRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentConfigRow(
                rs.getLong("id"),
                rs.getString("config_name"),
                rs.getString("app_id"),
                rs.getString("mch_id"),
                rs.getString("merchant_serial_no"),
                rs.getString("api_v3_key_ciphertext"),
                rs.getString("private_key_pem_ciphertext"),
                rs.getString("wechat_public_key_pem_ciphertext"),
                nullableLong(rs, "private_key_file_id"),
                nullableLong(rs, "merchant_certificate_file_id"),
                normalizeVerifyMode(PaymentVerifyMode.valueOf(rs.getString("verify_mode"))),
                rs.getString("wechat_public_key_id"),
                nullableLong(rs, "wechat_public_key_file_id"),
                rs.getString("notify_url"),
                rs.getString("refund_notify_url"),
                rs.getBoolean("enabled"),
                rs.getInt("secret_cipher_version"),
                rs.getString("secret_key_id")
        );
    }

    public static PaymentSecretCipher.SecretContext apiV3KeyContext(Long configId) {
        if (configId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new PaymentSecretCipher.SecretContext(
                "payment-config", configId.toString(), "api-v3-key");
    }

    public static PaymentSecretCipher.SecretContext privateKeyPemContext(Long configId) {
        return secretContext(configId, "private-key-pem");
    }

    public static PaymentSecretCipher.SecretContext wechatPublicKeyPemContext(Long configId) {
        return secretContext(configId, "wechat-public-key-pem");
    }

    private static PaymentSecretCipher.SecretContext secretContext(Long configId, String fieldName) {
        if (configId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new PaymentSecretCipher.SecretContext("payment-config", configId.toString(), fieldName);
    }

    private String decryptApiV3Key(PaymentConfigRow row) {
        return decryptMaterial(apiV3KeyContext(row.id()), row.apiV3KeyCiphertext(), row);
    }

    private String decryptMaterial(
            PaymentSecretCipher.SecretContext context,
            String ciphertext,
            PaymentConfigRow row
    ) {
        try {
            PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(context, ciphertext);
            if (decrypted.version() != row.secretCipherVersion()
                    || !decrypted.keyId().equals(nullToEmpty(row.secretKeyId()))) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
            }
            return decrypted.plaintext();
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
    }

    private boolean privateKeyMatches(PaymentConfigRow row, ResolvedPaymentConfig config) {
        if (!StringUtils.hasText(row.privateKeyPemCiphertext())) {
            return Objects.equals(row.privateKeyFileId(), config.privateKeyFileId());
        }
        return fingerprintsEqual(
                decryptMaterial(privateKeyPemContext(row.id()), row.privateKeyPemCiphertext(), row),
                config.privateKeyPem()
        );
    }

    private boolean publicKeyMatches(PaymentConfigRow row, ResolvedPaymentConfig config) {
        if (!StringUtils.hasText(row.wechatPublicKeyPemCiphertext())) {
            return Objects.equals(row.wechatPublicKeyFileId(), config.wechatPublicKeyFileId());
        }
        return fingerprintsEqual(
                decryptMaterial(
                        wechatPublicKeyPemContext(row.id()), row.wechatPublicKeyPemCiphertext(), row),
                config.wechatPublicKeyPem()
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private record PaymentConfigRow(
            Long id,
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3KeyCiphertext,
            String privateKeyPemCiphertext,
            String wechatPublicKeyPemCiphertext,
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            PaymentVerifyMode verifyMode,
            String wechatPublicKeyId,
            Long wechatPublicKeyFileId,
            String notifyUrl,
            String refundNotifyUrl,
            boolean enabled,
            int secretCipherVersion,
            String secretKeyId
    ) {
    }
}
