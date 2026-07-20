package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class PaymentConfigResolver {


    private final PaymentProperties properties;
    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;
    private final PrivateStorageFileService privateStorageFileService;
    private final PaymentConfigSourceSettingService sourceSettingService;
    private final PaymentConfigSnapshotStore snapshotStore;

    public PaymentConfigResolver(
            PaymentProperties properties,
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher,
            PrivateStorageFileService privateStorageFileService,
            PaymentConfigSourceSettingService sourceSettingService,
            PaymentConfigSnapshotStore snapshotStore
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
        this.privateStorageFileService = privateStorageFileService;
        this.sourceSettingService = sourceSettingService;
        this.snapshotStore = snapshotStore;
    }

    public ResolvedPaymentConfig resolve() {
        return resolve(properties, sourceSettingService.currentSource());
    }

    public ResolvedPaymentConfig resolve(PaymentProperties candidate) {
        PaymentConfigSource source = candidate.configSource() == null ? PaymentConfigSource.AUTO : candidate.configSource();
        return resolve(candidate, source);
    }

    public ResolvedPaymentConfig resolve(PaymentConfigSource source) {
        return resolve(properties, source == null ? PaymentConfigSource.AUTO : source);
    }

    /**
     * Resolves the configuration persisted on a payment order. Historical database configurations
     * remain usable after they are no longer the enabled/current configuration. Payments created
     * from environment configuration persist no database id, so a null id retains the normal
     * runtime source fallback.
     */
    public ResolvedPaymentConfig resolveForPaymentConfigId(Long paymentConfigId) {
        if (paymentConfigId == null) {
            return resolve();
        }
        PaymentConfigRow row = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled
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
     * legacy orders remain safe because their referenced row cannot be mutated. A versioned ENV
     * order first matches the live environment and then falls back to its encrypted historical
     * snapshot. Upgrade-era ENV rows without a fingerprint still fail closed.
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
            ResolvedPaymentConfig current = resolve();
            if (current.source() == PaymentConfigSource.ENV
                    && fingerprintsEqual(normalizedFingerprint, fingerprint(current))) {
                return current;
            }
        } catch (BusinessException ignored) {
            // The runtime source may be DB or temporarily unavailable; the snapshot is authoritative.
        }

        try {
            ResolvedPaymentConfig snapshot = snapshotStore.findEnvironmentConfig(normalizedFingerprint)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
            return requireFingerprint(snapshot, normalizedFingerprint);
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
    }

    /**
     * Persists the exact environment credential revision before a new payment order references it.
     * Database configurations are already immutable once referenced, so only their fingerprint is
     * returned. Snapshot encryption failure aborts payment preparation instead of creating an order
     * that cannot later be reconciled.
     */
    public String captureForPayment(ResolvedPaymentConfig config) {
        String configFingerprint = fingerprint(config);
        if (config.source() == PaymentConfigSource.ENV) {
            ResolvedPaymentConfig snapshot = snapshotStore.captureEnvironmentConfig(config, configFingerprint);
            requireFingerprint(snapshot, configFingerprint);
        }
        return configFingerprint;
    }

    /**
     * Linearizes creation of a DB-backed payment against administrator configuration updates.
     * Secret files were already read outside the transaction; under the row lock we compare the
     * provider-significant database fields and immutable file ids with that resolved object. The
     * payment insert then runs in the same transaction, so a concurrent update either happens
     * first and forces a retry, or waits and is rejected after the configuration becomes referenced.
     */
    public ResolvedPaymentConfig lockForPaymentCreation(ResolvedPaymentConfig config) {
        if (config == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (config.source() != PaymentConfigSource.DB) {
            return config;
        }
        if (config.configId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }

        PaymentConfigRow locked = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled
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
                    && fingerprintsEqual(secretCipher.decrypt(locked.apiV3KeyCiphertext()), config.apiV3Key())
                    && Objects.equals(locked.privateKeyFileId(), config.privateKeyFileId())
                    && Objects.equals(locked.merchantCertificateFileId(), config.merchantCertificateFileId())
                    && locked.verifyMode() == config.verifyMode()
                    && Objects.equals(locked.wechatPublicKeyId(), config.wechatPublicKeyId())
                    && Objects.equals(locked.wechatPublicKeyFileId(), config.wechatPublicKeyFileId())
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

    private ResolvedPaymentConfig resolve(PaymentProperties candidate, PaymentConfigSource source) {
        if (source == PaymentConfigSource.ENV) {
            return resolveEnv(candidate);
        }
        if (source == PaymentConfigSource.DB) {
            return resolveDb();
        }
        if (isCompleteEnv(candidate)) {
            return resolveEnv(candidate);
        }
        return resolveDb();
    }

    public ResolvedPaymentConfig resolveEnv(PaymentProperties candidate) {
        if (!isCompleteEnv(candidate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PaymentVerifyMode verifyMode = requireSupportedVerifyMode(candidate.verifyMode());
        return new ResolvedPaymentConfig(
                PaymentConfigSource.ENV,
                null,
                "Environment",
                candidate.enabled(),
                candidate.appId(),
                candidate.mchId(),
                candidate.merchantSerialNo(),
                candidate.apiV3Key(),
                readTextFile(candidate.privateKeyPath()),
                candidate.notifyUrl(),
                candidate.refundNotifyUrl(),
                verifyMode,
                nullToEmpty(candidate.publicKeyId()),
                readOptionalTextFile(candidate.publicKeyPath()),
                null,
                null,
                null
        );
    }

    public ResolvedPaymentConfig resolveDb() {
        PaymentConfigRow row = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled
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
                secretCipher.decrypt(row.apiV3KeyCiphertext()),
                readPrivateFile(row.privateKeyFileId()),
                row.notifyUrl(),
                row.refundNotifyUrl(),
                verifyMode,
                requiredPublicKeyId(verifyMode, row.wechatPublicKeyId()),
                readPublicKeyFile(verifyMode, row.wechatPublicKeyFileId()),
                row.privateKeyFileId(),
                row.merchantCertificateFileId(),
                row.wechatPublicKeyFileId()
        );
    }

    private boolean isCompleteEnv(PaymentProperties candidate) {
        return candidate.enabled()
                && StringUtils.hasText(candidate.appId())
                && StringUtils.hasText(candidate.mchId())
                && StringUtils.hasText(candidate.merchantSerialNo())
                && StringUtils.hasText(candidate.privateKeyPath())
                && StringUtils.hasText(candidate.apiV3Key())
                && StringUtils.hasText(candidate.notifyUrl())
                && StringUtils.hasText(candidate.refundNotifyUrl())
                && isCompleteVerifyMaterial(candidate);
    }

    private String readPrivateFile(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return privateStorageFileService.readSecretText(fileId);
    }

    private String readOptionalPrivateFile(Long fileId) {
        if (fileId == null) {
            return "";
        }
        return privateStorageFileService.readSecretText(fileId);
    }

    private boolean isCompleteVerifyMaterial(PaymentProperties candidate) {
        if (normalizeVerifyMode(candidate.verifyMode()) != PaymentVerifyMode.PUBLIC_KEY) {
            return false;
        }
        return StringUtils.hasText(candidate.publicKeyId())
                && StringUtils.hasText(candidate.publicKeyPath());
    }

    private String requiredPublicKeyId(PaymentVerifyMode verifyMode, String publicKeyId) {
        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY && !StringUtils.hasText(publicKeyId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return nullToEmpty(publicKeyId);
    }

    private String readPublicKeyFile(PaymentVerifyMode verifyMode, Long fileId) {
        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY) {
            return readPrivateFile(fileId);
        }
        return readOptionalPrivateFile(fileId);
    }

    private String readTextFile(String path) {
        if (!StringUtils.hasText(path)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String readOptionalTextFile(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        return readTextFile(path);
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
                nullableLong(rs, "private_key_file_id"),
                nullableLong(rs, "merchant_certificate_file_id"),
                normalizeVerifyMode(PaymentVerifyMode.valueOf(rs.getString("verify_mode"))),
                rs.getString("wechat_public_key_id"),
                nullableLong(rs, "wechat_public_key_file_id"),
                rs.getString("notify_url"),
                rs.getString("refund_notify_url"),
                rs.getBoolean("enabled")
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
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            PaymentVerifyMode verifyMode,
            String wechatPublicKeyId,
            Long wechatPublicKeyFileId,
            String notifyUrl,
            String refundNotifyUrl,
            boolean enabled
    ) {
    }
}
