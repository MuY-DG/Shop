package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.muybaby.shopserver.payment.config.PaymentSecretCipher.SecretContext;

/**
 * Read-only recovery store for historical environment payment configurations.
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

    Optional<ResolvedPaymentConfig> findHistoricalSnapshot(String fingerprint) {
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
        if (!"ENV".equals(rs.getString("config_source"))) {
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
                PaymentConfigSource.HISTORICAL_SNAPSHOT,
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

    private boolean validFingerprint(String fingerprint) {
        return fingerprint != null && FINGERPRINT_PATTERN.matcher(fingerprint).matches();
    }

}
