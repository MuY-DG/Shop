package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.muybaby.shopserver.payment.config.PaymentSecretCipher.SecretContext;

/**
 * Content-addressed, append-only storage for environment payment configurations.
 *
 * <p>Only public merchant identity fields are stored directly. Provider key material is encrypted
 * through {@link PaymentSecretCipher}; callers must still verify the decrypted configuration against
 * the content fingerprint before using it.</p>
 */
@Repository
public class PaymentConfigSnapshotStore {

    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;

    public PaymentConfigSnapshotStore(JdbcClient jdbcClient, PaymentSecretCipher secretCipher) {
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
    }

    ResolvedPaymentConfig captureEnvironmentConfig(ResolvedPaymentConfig config, String fingerprint) {
        requireEnvironmentConfig(config, fingerprint);
        Optional<ResolvedPaymentConfig> existing = findEnvironmentConfig(fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        PaymentSecretCipher.EncryptedSecret apiV3Key = secretCipher.encrypt(
                secretContext(fingerprint, "api-v3-key"), config.apiV3Key());
        PaymentSecretCipher.EncryptedSecret privateKeyPem = secretCipher.encrypt(
                secretContext(fingerprint, "private-key-pem"), config.privateKeyPem());
        PaymentSecretCipher.EncryptedSecret publicKeyPem = secretCipher.encrypt(
                secretContext(fingerprint, "wechat-public-key-pem"), config.wechatPublicKeyPem());
        requireSameEnvelope(apiV3Key, privateKeyPem, publicKeyPem);
        jdbcClient.sql("""
                        insert into payment_config_snapshot
                            (fingerprint, config_source, config_name, app_id, mch_id,
                             merchant_serial_no, api_v3_key_ciphertext, private_key_pem_ciphertext,
                             notify_url, refund_notify_url, verify_mode, wechat_public_key_id,
                             wechat_public_key_pem_ciphertext, secret_cipher_version, secret_key_id)
                        values
                            (:fingerprint, 'ENV', :configName, :appId, :mchId,
                             :merchantSerialNo, :apiV3KeyCiphertext, :privateKeyPemCiphertext,
                             :notifyUrl, :refundNotifyUrl, :verifyMode, :wechatPublicKeyId,
                             :wechatPublicKeyPemCiphertext, :secretCipherVersion, :secretKeyId)
                        on duplicate key update fingerprint = fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .param("configName", config.configName())
                .param("appId", config.appId())
                .param("mchId", config.mchId())
                .param("merchantSerialNo", config.merchantSerialNo())
                .param("apiV3KeyCiphertext", apiV3Key.ciphertext())
                .param("privateKeyPemCiphertext", privateKeyPem.ciphertext())
                .param("notifyUrl", config.notifyUrl())
                .param("refundNotifyUrl", config.refundNotifyUrl())
                .param("verifyMode", config.verifyMode().name())
                .param("wechatPublicKeyId", config.wechatPublicKeyId())
                .param("wechatPublicKeyPemCiphertext", publicKeyPem.ciphertext())
                .param("secretCipherVersion", apiV3Key.version())
                .param("secretKeyId", apiV3Key.keyId())
                .update();
        return findEnvironmentConfig(fingerprint)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
    }

    Optional<ResolvedPaymentConfig> findEnvironmentConfig(String fingerprint) {
        if (!validFingerprint(fingerprint)) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        select fingerprint, config_source, config_name, app_id, mch_id,
                               merchant_serial_no, api_v3_key_ciphertext, private_key_pem_ciphertext,
                               notify_url, refund_notify_url, verify_mode, wechat_public_key_id,
                               wechat_public_key_pem_ciphertext,
                               secret_cipher_version, secret_key_id
                        from payment_config_snapshot
                        where fingerprint = :fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .query(this::mapSnapshot)
                .optional();
    }

    private ResolvedPaymentConfig mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        if (!PaymentConfigSource.ENV.name().equals(rs.getString("config_source"))) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        PaymentVerifyMode verifyMode;
        try {
            verifyMode = PaymentVerifyMode.valueOf(rs.getString("verify_mode"));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        if (verifyMode != PaymentVerifyMode.PUBLIC_KEY) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        String fingerprint = rs.getString("fingerprint");
        int cipherVersion = rs.getInt("secret_cipher_version");
        String keyId = rs.getString("secret_key_id");
        PaymentSecretCipher.DecryptedSecret apiV3Key = decryptAndValidateMetadata(
                secretContext(fingerprint, "api-v3-key"),
                rs.getString("api_v3_key_ciphertext"), cipherVersion, keyId);
        PaymentSecretCipher.DecryptedSecret privateKeyPem = decryptAndValidateMetadata(
                secretContext(fingerprint, "private-key-pem"),
                rs.getString("private_key_pem_ciphertext"), cipherVersion, keyId);
        PaymentSecretCipher.DecryptedSecret publicKeyPem = decryptAndValidateMetadata(
                secretContext(fingerprint, "wechat-public-key-pem"),
                rs.getString("wechat_public_key_pem_ciphertext"), cipherVersion, keyId);
        return new ResolvedPaymentConfig(
                PaymentConfigSource.ENV,
                null,
                rs.getString("config_name"),
                true,
                rs.getString("app_id"),
                rs.getString("mch_id"),
                rs.getString("merchant_serial_no"),
                apiV3Key.plaintext(),
                privateKeyPem.plaintext(),
                rs.getString("notify_url"),
                rs.getString("refund_notify_url"),
                verifyMode,
                rs.getString("wechat_public_key_id"),
                publicKeyPem.plaintext(),
                null,
                null,
                null
        );
    }

    static SecretContext secretContext(String fingerprint, String fieldName) {
        return new SecretContext("payment-config-snapshot", fingerprint, fieldName);
    }

    private PaymentSecretCipher.DecryptedSecret decryptAndValidateMetadata(
            SecretContext context,
            String ciphertext,
            int expectedVersion,
            String expectedKeyId
    ) {
        PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(context, ciphertext);
        if (decrypted.version() != expectedVersion
                || !decrypted.keyId().equals(expectedKeyId == null ? "" : expectedKeyId)) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        return decrypted;
    }

    private void requireSameEnvelope(PaymentSecretCipher.EncryptedSecret... secrets) {
        if (secrets.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int version = secrets[0].version();
        String keyId = secrets[0].keyId();
        for (PaymentSecretCipher.EncryptedSecret secret : secrets) {
            if (secret.version() != version || !keyId.equals(secret.keyId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private void requireEnvironmentConfig(ResolvedPaymentConfig config, String fingerprint) {
        boolean valid = config != null
                && config.source() == PaymentConfigSource.ENV
                && config.configId() == null
                && validFingerprint(fingerprint)
                && validText(config.configName(), 80)
                && validText(config.appId(), 64)
                && validText(config.mchId(), 32)
                && validText(config.merchantSerialNo(), 128)
                && StringUtils.hasText(config.apiV3Key())
                && StringUtils.hasText(config.privateKeyPem())
                && validText(config.notifyUrl(), 255)
                && validText(config.refundNotifyUrl(), 255)
                && config.verifyMode() == PaymentVerifyMode.PUBLIC_KEY
                && validText(config.wechatPublicKeyId(), 128)
                && StringUtils.hasText(config.wechatPublicKeyPem());
        if (!valid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean validFingerprint(String fingerprint) {
        return fingerprint != null && FINGERPRINT_PATTERN.matcher(fingerprint).matches();
    }

    private boolean validText(String value, int maxLength) {
        return StringUtils.hasText(value) && value.length() <= maxLength;
    }
}
