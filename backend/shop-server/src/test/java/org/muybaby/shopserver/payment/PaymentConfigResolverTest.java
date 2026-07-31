package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;
import org.muybaby.shopserver.payment.config.AesGcmPaymentSecretCipher;
import org.muybaby.shopserver.payment.config.MaskedPaymentConfig;
import org.muybaby.shopserver.payment.config.PaymentConfigMasker;
import org.muybaby.shopserver.payment.config.PaymentConfigIdentityValidator;
import org.muybaby.shopserver.payment.config.PaymentConfigSnapshotStore;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentConfigSourceSettingService;
import org.muybaby.shopserver.payment.config.PaymentNotificationConfigSelector;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private PaymentConfigSnapshotStore snapshotStore;

    @Autowired
    private PaymentConfigSourceSettingService sourceSettingService;

    @Autowired
    private PrivateStorageFileService privateStorageFileService;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearPaymentConfigState() {
        jdbcClient.sql("delete from payment_runtime_setting").update();
        jdbcClient.sql("delete from payment_order where out_trade_no like 'CONFIG-SNAPSHOT-%'").update();
        jdbcClient.sql("delete from payment_config_snapshot").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_asset where object_key like 'test/%'").update();
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
                15
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
                15
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
                15
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
                15
        )))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void persistedConfigIdResolvesDisabledHistoryWhileNullUsesCurrentAndMissingMaterialFails() {
        Long privateKeyFileId = insertPrivateStorageFile(
                22201L, "private/payment/history-merchant.pem", PRIVATE_KEY_PEM);
        Long publicKeyFileId = insertPrivateStorageFile(
                22202L, "private/payment/history-public.pem", PUBLIC_KEY_PEM);
        insertDbConfig(22203L, "Historical Config", false, "history", privateKeyFileId, publicKeyFileId);
        insertDbConfig(22204L, "Current Config", true, "current", privateKeyFileId, publicKeyFileId);

        ResolvedPaymentConfig historical = resolver.resolveForPaymentConfigId(22203L);
        ResolvedPaymentConfig current = resolver.resolveForPaymentConfigId(null);

        assertThat(historical.configId()).isEqualTo(22203L);
        assertThat(historical.configName()).isEqualTo("Historical Config");
        assertThat(historical.enabled()).isFalse();
        assertThat(historical.mchId()).isEqualTo("mch_history");
        assertThat(current.configId()).isEqualTo(22204L);
        assertThat(current.configName()).isEqualTo("Current Config");

        String historicalFingerprint = resolver.fingerprint(historical);
        assertThat(historicalFingerprint).hasSize(64);
        assertThat(resolver.resolveForPayment(22203L, historicalFingerprint).configId()).isEqualTo(22203L);
        // Legacy DB orders remain recoverable because their historical row is immutable.
        assertThat(resolver.resolveForPayment(22203L, "").configId()).isEqualTo(22203L);
        assertThatThrownBy(() -> resolver.resolveForPayment(22203L, "0".repeat(64)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
        // Legacy ENV orders have no persisted credentials and must not fall back to a rotated config.
        assertThatThrownBy(() -> resolver.resolveForPayment(null, ""))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));

        assertThatThrownBy(() -> resolver.resolveForPaymentConfigId(22999L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        jdbcClient.sql("delete from storage_asset where id = :fileId")
                .param("fileId", publicKeyFileId)
                .update();
        assertThatThrownBy(() -> resolver.resolveForPaymentConfigId(22203L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    @Test
    void encryptedEnvironmentSnapshotSurvivesCredentialRotationAndCannotBeOverwritten() throws Exception {
        PaymentProperties historicalProperties = environmentProperties("history");
        PaymentProperties rotatedProperties = environmentProperties("rotated");
        ResolvedPaymentConfig historicalConfig = resolver.resolve(historicalProperties);
        String historicalFingerprint = resolver.captureForPayment(historicalConfig);
        SnapshotCiphertexts before = snapshotCiphertexts(historicalFingerprint);

        assertThat(before.apiV3Key()).startsWith("v1:").doesNotContain("api_v3_history");
        assertThat(before.privateKey()).startsWith("v1:").doesNotContain("private-key-history");
        assertThat(before.publicKey()).startsWith("v1:").doesNotContain("public-key-history");

        // Capturing an already known content identity is a no-op, not a secret re-encryption/update.
        assertThat(resolver.captureForPayment(historicalConfig)).isEqualTo(historicalFingerprint);
        assertThat(snapshotCiphertexts(historicalFingerprint)).isEqualTo(before);

        jdbcClient.sql("insert into payment_runtime_setting (id, config_source) values (1, 'ENV')").update();
        PaymentConfigResolver rotatedResolver = resolverFor(rotatedProperties);
        assertThat(rotatedResolver.fingerprint(rotatedResolver.resolve()))
                .isNotEqualTo(historicalFingerprint);

        ResolvedPaymentConfig recovered = rotatedResolver.resolveForPayment(null, historicalFingerprint);
        assertThat(recovered.source()).isEqualTo(PaymentConfigSource.ENV);
        assertThat(recovered.mchId()).isEqualTo("mch_history");
        assertThat(recovered.apiV3Key()).isEqualTo("api_v3_history");
        assertThat(recovered.privateKeyPem()).contains("private-key-history");
        assertThat(recovered.wechatPublicKeyPem()).contains("public-key-history");

        jdbcClient.sql("""
                        update payment_config_snapshot
                        set app_id = 'tampered-app-id'
                        where fingerprint = :fingerprint
                        """)
                .param("fingerprint", historicalFingerprint)
                .update();
        assertThatThrownBy(() -> rotatedResolver.resolveForPayment(null, historicalFingerprint))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
    }

    @Test
    void notificationSelectorCanDecryptWithHistoricalEnvironmentSnapshotAfterRotation() throws Exception {
        PaymentProperties historicalProperties = environmentProperties("notify_history");
        PaymentProperties rotatedProperties = environmentProperties("notify_rotated");
        ResolvedPaymentConfig historicalConfig = resolver.resolve(historicalProperties);
        String historicalFingerprint = resolver.captureForPayment(historicalConfig);
        jdbcClient.sql("insert into payment_runtime_setting (id, config_source) values (1, 'ENV')").update();
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint, out_trade_no,
                             payer_openid, status, amount_cent, currency, expires_at)
                        values
                            (22991, null, :fingerprint, 'CONFIG-SNAPSHOT-NOTIFY',
                             'openid-snapshot', 'PAYING', 100, 'CNY', current_timestamp)
                        """)
                .param("fingerprint", historicalFingerprint)
                .update();

        PaymentConfigResolver rotatedResolver = resolverFor(rotatedProperties);
        PaymentNotificationConfigSelector selector = new PaymentNotificationConfigSelector(
                jdbcClient,
                rotatedResolver,
                new PaymentConfigIdentityValidator(rotatedResolver),
                new PaymentNotificationRouteService(new PaymentNotificationRouteProperties(false)));
        PaymentNotificationConfigSelector.ParsedNotification<String> parsed = selector.parse(config -> {
            if (!"mch_notify_history".equals(config.mchId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return "CONFIG-SNAPSHOT-NOTIFY";
        }, PaymentNotificationConfigSelector.NotificationRoute::payment);

        assertThat(parsed.notification()).isEqualTo("CONFIG-SNAPSHOT-NOTIFY");
        assertThat(parsed.config().apiV3Key()).isEqualTo("api_v3_notify_history");
    }

    @Test
    void dbSecretEncryptionFailsValidationWhenSecretKeyIsMissing() {
        PaymentSecretCipher cipherWithoutKey =
                new AesGcmPaymentSecretCipher(legacyEncryptionProperties(""));

        assertThatThrownBy(() -> cipherWithoutKey.encrypt("api_v3_secret_test"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void environmentPaymentIsNotSnapshottedWhenRootEncryptionKeyIsMissing() throws Exception {
        PaymentProperties sourceProperties = environmentProperties("missing_root_key");
        ResolvedPaymentConfig environmentConfig = resolver.resolve(sourceProperties);
        PaymentSecretCipher cipherWithoutKey = new AesGcmPaymentSecretCipher(
                legacyEncryptionProperties(""));
        PaymentConfigSnapshotStore storeWithoutKey = new PaymentConfigSnapshotStore(
                jdbcClient, cipherWithoutKey);
        PaymentConfigResolver resolverWithoutKey = new PaymentConfigResolver(
                sourceProperties,
                jdbcClient,
                cipherWithoutKey,
                privateStorageFileService,
                sourceSettingService,
                storeWithoutKey
        );

        assertThatThrownBy(() -> resolverWithoutKey.captureForPayment(environmentConfig))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(jdbcClient.sql("select count(*) from payment_config_snapshot")
                .query(Integer.class)
                .single()).isZero();
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
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:id, 'SECRET', 'DOCUMENT', 'PRIVATE', 'TENCENT_COS', '', :objectKey, 'key.pem',
                             'text/plain', 'pem', :sizeBytes, '', 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("id", id)
                .param("objectKey", uniqueObjectKey)
                .param("sizeBytes", bytes.length)
                .update();
        return id;
    }

    private PaymentConfigResolver resolverFor(PaymentProperties candidate) {
        return new PaymentConfigResolver(
                candidate,
                jdbcClient,
                secretCipher,
                privateStorageFileService,
                sourceSettingService,
                snapshotStore
        );
    }

    private SecretEncryptionProperties legacyEncryptionProperties(String legacyKey) {
        return new SecretEncryptionProperties(
                1,
                "",
                "",
                legacyKey,
                false,
                Duration.ofMinutes(1),
                50
        );
    }

    private PaymentProperties environmentProperties(String suffix) throws Exception {
        Path privateKeyPath = tempDir.resolve("merchant-private-" + suffix + ".pem");
        Path publicKeyPath = tempDir.resolve("wechat-public-" + suffix + ".pem");
        Files.writeString(privateKeyPath, """
                -----BEGIN PRIVATE KEY-----
                private-key-%s
                -----END PRIVATE KEY-----
                """.formatted(suffix), StandardCharsets.UTF_8);
        Files.writeString(publicKeyPath, """
                -----BEGIN PUBLIC KEY-----
                public-key-%s
                -----END PUBLIC KEY-----
                """.formatted(suffix), StandardCharsets.UTF_8);
        return new PaymentProperties(
                true,
                false,
                PaymentConfigSource.ENV,
                "wx_" + suffix,
                "mch_" + suffix,
                "serial_" + suffix,
                privateKeyPath.toString(),
                "api_v3_" + suffix,
                "https://" + suffix + ".test/wxpay/pay/notify",
                "https://" + suffix + ".test/wxpay/refund/notify",
                PaymentVerifyMode.PUBLIC_KEY,
                "public_" + suffix,
                publicKeyPath.toString(),
                15
        );
    }

    private SnapshotCiphertexts snapshotCiphertexts(String fingerprint) {
        return jdbcClient.sql("""
                        select api_v3_key_ciphertext,
                               private_key_pem_ciphertext,
                               wechat_public_key_pem_ciphertext
                        from payment_config_snapshot
                        where fingerprint = :fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .query((rs, rowNum) -> new SnapshotCiphertexts(
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getString("private_key_pem_ciphertext"),
                        rs.getString("wechat_public_key_pem_ciphertext")
                ))
                .single();
    }

    private record SnapshotCiphertexts(String apiV3Key, String privateKey, String publicKey) {
    }

    private void insertDbConfig(
            Long id,
            String configName,
            boolean enabled,
            String suffix,
            Long privateKeyFileId,
            Long publicKeyFileId
    ) {
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (:id, :configName, :appId, :mchId, :merchantSerialNo, :ciphertext,
                             :privateKeyFileId, null, 'PUBLIC_KEY', :publicKeyId, :publicKeyFileId,
                             :notifyUrl, :refundNotifyUrl, :enabled, 'ACTIVE')
                        """)
                .param("id", id)
                .param("configName", configName)
                .param("appId", "wx_" + suffix)
                .param("mchId", "mch_" + suffix)
                .param("merchantSerialNo", "serial_" + suffix)
                .param("ciphertext", secretCipher.encrypt("api_v3_" + suffix))
                .param("privateKeyFileId", privateKeyFileId)
                .param("publicKeyId", "public_" + suffix)
                .param("publicKeyFileId", publicKeyFileId)
                .param("notifyUrl", "https://" + suffix + ".test/wxpay/pay/notify")
                .param("refundNotifyUrl", "https://" + suffix + ".test/wxpay/refund/notify")
                .param("enabled", enabled)
                .update();
    }
}
