package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.AesGcmPaymentSecretCipher;
import org.muybaby.shopserver.payment.config.MaskedPaymentConfig;
import org.muybaby.shopserver.payment.config.PaymentConfigMasker;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

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

    @Autowired
    private PaymentConfigMasker masker;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearPaymentConfigState() {
        jdbcClient.sql("delete from payment_runtime_setting").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_file where object_key like 'test/%'").update();
    }

    @Test
    void envConfigResolvesWhenCompleteAndSourceModeIsAuto() throws Exception {
        Path privateKeyPath = tempDir.resolve("merchant_private_key.pem");
        Path publicKeyPath = tempDir.resolve("wechat_public_key.pem");
        Files.writeString(privateKeyPath, PRIVATE_KEY_PEM, StandardCharsets.UTF_8);
        Files.writeString(publicKeyPath, PUBLIC_KEY_PEM, StandardCharsets.UTF_8);

        PaymentProperties envProperties = new PaymentProperties(
                true,
                false,
                PaymentConfigSource.AUTO,
                "wx_test_app",
                "mch_test",
                "serial_test",
                privateKeyPath.toString(),
                "api_v3_secret_test",
                "https://pay.test/wxpay/pay/notify",
                "https://pay.test/wxpay/refund/notify",
                PaymentVerifyMode.PUBLIC_KEY,
                "pub_key_test",
                publicKeyPath.toString(),
                15,
                "0123456789abcdef0123456789abcdef"
        );

        ResolvedPaymentConfig resolved = resolver.resolve(envProperties);

        assertThat(resolved.source()).isEqualTo(PaymentConfigSource.ENV);
        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.appId()).isEqualTo("wx_test_app");
        assertThat(resolved.mchId()).isEqualTo("mch_test");
        assertThat(resolved.merchantSerialNo()).isEqualTo("serial_test");
        assertThat(resolved.apiV3Key()).isEqualTo("api_v3_secret_test");
        assertThat(resolved.privateKeyPem()).isEqualTo(PRIVATE_KEY_PEM);
        assertThat(resolved.notifyUrl()).isEqualTo("https://pay.test/wxpay/pay/notify");
        assertThat(resolved.refundNotifyUrl()).isEqualTo("https://pay.test/wxpay/refund/notify");
        assertThat(resolved.verifyMode()).isEqualTo(PaymentVerifyMode.PUBLIC_KEY);
        assertThat(resolved.wechatPublicKeyId()).isEqualTo("pub_key_test");
        assertThat(resolved.wechatPublicKeyPem()).isEqualTo(PUBLIC_KEY_PEM);
    }

    @Test
    void envConfigRejectsCertificateVerifyMode() throws Exception {
        Path privateKeyPath = tempDir.resolve("merchant_private_key.pem");
        Files.writeString(privateKeyPath, PRIVATE_KEY_PEM, StandardCharsets.UTF_8);

        PaymentProperties envProperties = new PaymentProperties(
                true,
                false,
                PaymentConfigSource.ENV,
                "wx_test_app",
                "mch_test",
                "serial_test",
                privateKeyPath.toString(),
                "api_v3_secret_test",
                "https://pay.test/wxpay/pay/notify",
                "https://pay.test/wxpay/refund/notify",
                PaymentVerifyMode.CERTIFICATE,
                "",
                "",
                15,
                "0123456789abcdef0123456789abcdef"
        );

        assertThatThrownBy(() -> resolver.resolve(envProperties))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void dbConfigResolvesWhenSourceModeIsDbAndDecryptsOnlyInResolver() {
        Long privateKeyFileId = insertPrivateStorageFile(22001L, "private/payment/merchant.pem", PRIVATE_KEY_PEM);
        Long publicKeyFileId = insertPrivateStorageFile(22002L, "private/payment/wechat_public.pem", PUBLIC_KEY_PEM);
        String ciphertext = secretCipher.encrypt("api_v3_secret_test");

        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (22003, 'DB Config', 'wx_db_app', 'mch_db', 'serial_db',
                             :ciphertext, :privateKeyFileId, null, 'PUBLIC_KEY',
                             'pub_key_db', :publicKeyFileId, 'https://db.test/wxpay/pay/notify',
                             'https://db.test/wxpay/refund/notify', true, 'ACTIVE')
                        """)
                .param("ciphertext", ciphertext)
                .param("privateKeyFileId", privateKeyFileId)
                .param("publicKeyFileId", publicKeyFileId)
                .update();

        assertThat(ciphertext).startsWith("v1:");
        assertThat(ciphertext).doesNotContain("api_v3_secret_test");

        ResolvedPaymentConfig resolved = resolver.resolve(new PaymentProperties(
                true,
                false,
                PaymentConfigSource.DB,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                PaymentVerifyMode.PUBLIC_KEY,
                "",
                "",
                15,
                "0123456789abcdef0123456789abcdef"
        ));

        assertThat(resolved.source()).isEqualTo(PaymentConfigSource.DB);
        assertThat(resolved.appId()).isEqualTo("wx_db_app");
        assertThat(resolved.mchId()).isEqualTo("mch_db");
        assertThat(resolved.merchantSerialNo()).isEqualTo("serial_db");
        assertThat(resolved.apiV3Key()).isEqualTo("api_v3_secret_test");
        assertThat(resolved.privateKeyPem()).isEqualTo(PRIVATE_KEY_PEM);
        assertThat(resolved.notifyUrl()).isEqualTo("https://db.test/wxpay/pay/notify");
        assertThat(resolved.refundNotifyUrl()).isEqualTo("https://db.test/wxpay/refund/notify");
        assertThat(resolved.verifyMode()).isEqualTo(PaymentVerifyMode.PUBLIC_KEY);
        assertThat(resolved.wechatPublicKeyId()).isEqualTo("pub_key_db");
        assertThat(resolved.wechatPublicKeyPem()).isEqualTo(PUBLIC_KEY_PEM);
    }

    @Test
    void dbConfigRejectsUnsupportedCertificateVerifyModeBeforeRuntimeProviderUse() {
        Long privateKeyFileId = insertPrivateStorageFile(22101L, "private/payment/merchant.pem", PRIVATE_KEY_PEM);
        Long merchantCertificateFileId = insertPrivateStorageFile(22102L, "private/payment/merchant_cert.pem", PUBLIC_KEY_PEM);
        String ciphertext = secretCipher.encrypt("api_v3_secret_test");

        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (22103, 'DB Certificate Config', 'wx_db_app', 'mch_db', 'serial_db',
                             :ciphertext, :privateKeyFileId, :merchantCertificateFileId, 'CERTIFICATE',
                             '', null, 'https://db.test/wxpay/pay/notify',
                             'https://db.test/wxpay/refund/notify', true, 'ACTIVE')
                        """)
                .param("ciphertext", ciphertext)
                .param("privateKeyFileId", privateKeyFileId)
                .param("merchantCertificateFileId", merchantCertificateFileId)
                .update();

        assertThatThrownBy(() -> resolver.resolve(new PaymentProperties(
                true,
                false,
                PaymentConfigSource.DB,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                PaymentVerifyMode.PUBLIC_KEY,
                "",
                "",
                15,
                "0123456789abcdef0123456789abcdef"
        )))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void dbSecretEncryptionFailsValidationWhenSecretKeyIsMissing() {
        PaymentSecretCipher cipherWithoutKey = new AesGcmPaymentSecretCipher(new PaymentProperties(
                true,
                false,
                PaymentConfigSource.DB,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                PaymentVerifyMode.PUBLIC_KEY,
                "",
                "",
                15,
                ""
        ));

        assertThatThrownBy(() -> cipherWithoutKey.encrypt("api_v3_secret_test"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void maskedConfigDoesNotExposeSecretsOrKeyContents() throws Exception {
        ResolvedPaymentConfig resolved = new ResolvedPaymentConfig(
                PaymentConfigSource.ENV,
                null,
                "Environment",
                true,
                "wx_test_app",
                "mch_test",
                "serial_test",
                "api_v3_secret_test",
                PRIVATE_KEY_PEM,
                "https://pay.test/wxpay/pay/notify",
                "https://pay.test/wxpay/refund/notify",
                PaymentVerifyMode.PUBLIC_KEY,
                "pub_key_test",
                PUBLIC_KEY_PEM,
                null,
                null,
                null
        );

        MaskedPaymentConfig masked = masker.mask(resolved);
        String json = objectMapper.writeValueAsString(masked);

        assertThat(masked.source()).isEqualTo(PaymentConfigSource.ENV);
        assertThat(masked.appIdMasked()).isEqualTo("wx_******app");
        assertThat(masked.mchIdMasked()).isEqualTo("mc***st");
        assertThat(masked.merchantSerialNoMasked()).isEqualTo("ser******est");
        assertThat(masked.apiV3KeyConfigured()).isTrue();
        assertThat(json)
                .doesNotContain("api_v3_secret_test")
                .doesNotContain("test-private-key-material")
                .doesNotContain("test-public-key-material")
                .doesNotContain("privateKeyPem")
                .doesNotContain("wechatPublicKeyPem");
    }

    private Long insertPrivateStorageFile(Long id, String objectKey, String content) {
        String uniqueObjectKey = "test/" + UUID.randomUUID() + "/" + objectKey;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(uniqueObjectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_file
                            (id, purpose, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:id, 'PAYMENT_CERTIFICATE', 'PRIVATE', 'LOCAL', '', :objectKey, 'key.pem',
                             'text/plain', 'pem', :sizeBytes, '', 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("id", id)
                .param("objectKey", uniqueObjectKey)
                .param("sizeBytes", bytes.length)
                .update();
        return id;
    }
}
