package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;
import org.muybaby.shopserver.payment.config.AesGcmPaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentConfigResolverTest {

    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            test-private-key-material
            -----END PRIVATE KEY-----
            """;
    private static final String PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            test-public-key-material
            -----END PUBLIC KEY-----
            """;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PaymentConfigResolver resolver;

    @Autowired
    private PaymentSecretCipher secretCipher;

    @BeforeEach
    void clearPaymentConfigState() {
        jdbcClient.sql("delete from payment_config").update();
    }

    @Test
    void runtimeResolvesOnlyTheEnabledDatabaseConfig() {
        insertDbConfig(22003L, "DB Config", true, "db");

        ResolvedPaymentConfig resolved = resolver.resolve();

        assertThat(resolved.source()).isEqualTo(PaymentConfigSource.DB);
        assertThat(resolved.configId()).isEqualTo(22003L);
        assertThat(resolved.appId()).isEqualTo("wx_db");
        assertThat(resolved.mchId()).isEqualTo("mch_db");
        assertThat(resolved.apiV3Key()).isEqualTo("api_v3_db");
        assertThat(resolved.privateKeyPem()).isEqualTo(PRIVATE_KEY_PEM);
        assertThat(resolved.wechatPublicKeyPem()).isEqualTo(PUBLIC_KEY_PEM);
    }

    @Test
    void runtimeHasNoEnvironmentFallbackWhenNoDatabaseConfigIsEnabled() {
        assertThat(resolver.resolveAvailable()).isEmpty();
        assertThatThrownBy(resolver::resolve)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void historicalDatabaseConfigRequiresItsCompleteImmutableIdentity() {
        insertDbConfig(22203L, "Historical Config", false, "history");
        insertDbConfig(22204L, "Current Config", true, "current");

        ResolvedPaymentConfig historical = resolver.resolveForPaymentConfigId(22203L);
        ResolvedPaymentConfig current = resolver.resolve();

        assertThat(historical.configName()).isEqualTo("Historical Config");
        assertThat(historical.enabled()).isFalse();
        assertThat(current.configId()).isEqualTo(22204L);
        String fingerprint = resolver.fingerprint(historical);
        assertThat(resolver.resolveForPayment(22203L, fingerprint).configId()).isEqualTo(22203L);
        assertThatThrownBy(() -> resolver.resolveForPayment(22203L, ""))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
        assertThatThrownBy(() -> resolver.resolveForPaymentConfigId(null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
        assertThatThrownBy(() -> resolver.resolveForPayment(null, ""))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
    }

    @Test
    void newPaymentsCaptureDatabaseConfigIdentity() {
        insertDbConfig(22303L, "Capture Config", true, "capture_db");
        ResolvedPaymentConfig database = resolver.resolve();
        assertThat(resolver.captureForPayment(database)).isEqualTo(resolver.fingerprint(database));
    }

    @Test
    void encryptionConfigurationRequiresAnActiveV2Key() {
        assertThatThrownBy(() -> new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties("missing", "", false, null, 50)))
                .isInstanceOf(IllegalStateException.class);
    }

    private void insertDbConfig(
            Long id,
            String configName,
            boolean enabled,
            String suffix
    ) {
        PaymentSecretCipher.EncryptedSecret apiV3Key = secretCipher.encrypt(
                PaymentConfigResolver.apiV3KeyContext(id), "api_v3_" + suffix);
        PaymentSecretCipher.EncryptedSecret privateKey = secretCipher.encrypt(
                PaymentConfigResolver.privateKeyPemContext(id), PRIVATE_KEY_PEM);
        PaymentSecretCipher.EncryptedSecret publicKey = secretCipher.encrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(id), PUBLIC_KEY_PEM);
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                             enabled, status, secret_cipher_version, secret_key_id)
                        values
                            (:id, :configName, :appId, :mchId, :merchantSerialNo,
                             :ciphertext, :privateKey, :publicKey,
                             'PUBLIC_KEY', :publicKeyId, :notifyUrl, :refundNotifyUrl,
                             :enabled, 'ACTIVE', :cipherVersion, :keyId)
                        """)
                .param("id", id)
                .param("configName", configName)
                .param("appId", "wx_" + suffix)
                .param("mchId", "mch_" + suffix)
                .param("merchantSerialNo", "serial_" + suffix)
                .param("ciphertext", apiV3Key.ciphertext())
                .param("privateKey", privateKey.ciphertext())
                .param("publicKey", publicKey.ciphertext())
                .param("publicKeyId", "public_" + suffix)
                .param("notifyUrl", "https://" + suffix + ".test/wxpay/pay/notify")
                .param("refundNotifyUrl", "https://" + suffix + ".test/wxpay/refund/notify")
                .param("enabled", enabled)
                .param("cipherVersion", apiV3Key.version())
                .param("keyId", apiV3Key.keyId())
                .update();
    }
}
