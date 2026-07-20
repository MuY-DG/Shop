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

        String apiV3KeyCiphertext = secretCipher.encrypt(config.apiV3Key());
        String privateKeyPemCiphertext = secretCipher.encrypt(config.privateKeyPem());
        String publicKeyPemCiphertext = secretCipher.encrypt(config.wechatPublicKeyPem());
        jdbcClient.sql("""
                        insert into payment_config_snapshot
                            (fingerprint, config_source, config_name, app_id, mch_id,
                             merchant_serial_no, api_v3_key_ciphertext, private_key_pem_ciphertext,
                             notify_url, refund_notify_url, verify_mode, wechat_public_key_id,
                             wechat_public_key_pem_ciphertext)
                        values
                            (:fingerprint, 'ENV', :configName, :appId, :mchId,
                             :merchantSerialNo, :apiV3KeyCiphertext, :privateKeyPemCiphertext,
                             :notifyUrl, :refundNotifyUrl, :verifyMode, :wechatPublicKeyId,
                             :wechatPublicKeyPemCiphertext)
                        on duplicate key update fingerprint = fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .param("configName", config.configName())
                .param("appId", config.appId())
                .param("mchId", config.mchId())
                .param("merchantSerialNo", config.merchantSerialNo())
                .param("apiV3KeyCiphertext", apiV3KeyCiphertext)
                .param("privateKeyPemCiphertext", privateKeyPemCiphertext)
                .param("notifyUrl", config.notifyUrl())
                .param("refundNotifyUrl", config.refundNotifyUrl())
                .param("verifyMode", config.verifyMode().name())
                .param("wechatPublicKeyId", config.wechatPublicKeyId())
                .param("wechatPublicKeyPemCiphertext", publicKeyPemCiphertext)
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
                               wechat_public_key_pem_ciphertext
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
        return new ResolvedPaymentConfig(
                PaymentConfigSource.ENV,
                null,
                rs.getString("config_name"),
                true,
                rs.getString("app_id"),
                rs.getString("mch_id"),
                rs.getString("merchant_serial_no"),
                secretCipher.decrypt(rs.getString("api_v3_key_ciphertext")),
                secretCipher.decrypt(rs.getString("private_key_pem_ciphertext")),
                rs.getString("notify_url"),
                rs.getString("refund_notify_url"),
                verifyMode,
                rs.getString("wechat_public_key_id"),
                secretCipher.decrypt(rs.getString("wechat_public_key_pem_ciphertext")),
                null,
                null,
                null
        );
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
