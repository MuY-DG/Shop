package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;
import org.muybaby.shopserver.payment.config.AesGcmPaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentConfigIdentityValidator;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentNotificationConfigSelector;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
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
    private StorageProvider storageProvider;

    @BeforeEach
    void clearPaymentConfigState() {
        jdbcClient.sql("delete from payment_order where out_trade_no like 'CONFIG-SNAPSHOT-%'").update();
        jdbcClient.sql("delete from payment_config_snapshot").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_asset where object_key like 'test/%'").update();
    }

    @Test
    void runtimeResolvesOnlyTheEnabledDatabaseConfig() {
        Long privateKeyFileId = insertPrivateStorageFile(22001L, "private/payment/merchant.pem", PRIVATE_KEY_PEM);
        Long publicKeyFileId = insertPrivateStorageFile(22002L, "private/payment/wechat_public.pem", PUBLIC_KEY_PEM);
        insertDbConfig(22003L, "DB Config", true, "db", privateKeyFileId, publicKeyFileId);

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
    void databaseConfigRejectsUnsupportedCertificateVerifyMode() {
        Long privateKeyFileId = insertPrivateStorageFile(22101L, "private/payment/merchant.pem", PRIVATE_KEY_PEM);
        String ciphertext = secretCipher.encrypt("api_v3_secret_test");
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (22103, 'DB Certificate Config', 'wx_db_app', 'mch_db', 'serial_db',
                             :ciphertext, '', '', :privateKeyFileId, null, 'CERTIFICATE', '', null,
                             'https://db.test/wxpay/pay/notify',
                             'https://db.test/wxpay/refund/notify', true, 'ACTIVE')
                        """)
                .param("ciphertext", ciphertext)
                .param("privateKeyFileId", privateKeyFileId)
                .update();

        assertThatThrownBy(resolver::resolve)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void historicalDatabaseConfigRemainsResolvableButNullIdCannotSelectLiveRuntime() {
        Long privateKeyFileId = insertPrivateStorageFile(
                22201L, "private/payment/history-merchant.pem", PRIVATE_KEY_PEM);
        Long publicKeyFileId = insertPrivateStorageFile(
                22202L, "private/payment/history-public.pem", PUBLIC_KEY_PEM);
        insertDbConfig(22203L, "Historical Config", false, "history", privateKeyFileId, publicKeyFileId);
        insertDbConfig(22204L, "Current Config", true, "current", privateKeyFileId, publicKeyFileId);

        ResolvedPaymentConfig historical = resolver.resolveForPaymentConfigId(22203L);
        ResolvedPaymentConfig current = resolver.resolve();

        assertThat(historical.configName()).isEqualTo("Historical Config");
        assertThat(historical.enabled()).isFalse();
        assertThat(current.configId()).isEqualTo(22204L);
        String fingerprint = resolver.fingerprint(historical);
        assertThat(resolver.resolveForPayment(22203L, fingerprint).configId()).isEqualTo(22203L);
        assertThat(resolver.resolveForPayment(22203L, "").configId()).isEqualTo(22203L);
        assertThatThrownBy(() -> resolver.resolveForPaymentConfigId(null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
        assertThatThrownBy(() -> resolver.resolveForPayment(null, ""))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
    }

    @Test
    void historicalEnvironmentSnapshotIsReadOnlyAndStillSupportsRecovery() {
        HistoricalSnapshot snapshot = insertHistoricalEnvironmentSnapshot("history");

        ResolvedPaymentConfig recovered = resolver.resolveForPayment(null, snapshot.fingerprint());
        assertThat(recovered.source()).isEqualTo(PaymentConfigSource.HISTORICAL_SNAPSHOT);
        assertThat(recovered.mchId()).isEqualTo("mch_history");
        assertThat(recovered.apiV3Key()).isEqualTo("api_v3_history");
        assertThat(snapshot.apiV3KeyCiphertext()).doesNotContain("api_v3_history");
        assertThatThrownBy(() -> resolver.captureForPayment(snapshot.config()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));

        jdbcClient.sql("""
                        update payment_config_snapshot
                        set app_id = 'tampered-app-id'
                        where fingerprint = :fingerprint
                        """)
                .param("fingerprint", snapshot.fingerprint())
                .update();
        assertThatThrownBy(() -> resolver.resolveForPayment(null, snapshot.fingerprint()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
    }

    @Test
    void notificationSelectorCanUseHistoricalEnvironmentSnapshot() {
        HistoricalSnapshot snapshot = insertHistoricalEnvironmentSnapshot("notify_history");
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint, out_trade_no,
                             payer_openid, status, amount_cent, currency, expires_at)
                        values
                            (22991, null, :fingerprint, 'CONFIG-SNAPSHOT-NOTIFY',
                             'openid-snapshot', 'PAYING', 100, 'CNY', current_timestamp)
                        """)
                .param("fingerprint", snapshot.fingerprint())
                .update();

        PaymentNotificationConfigSelector selector = new PaymentNotificationConfigSelector(
                jdbcClient,
                resolver,
                new PaymentConfigIdentityValidator(resolver),
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
    void newPaymentsRejectHistoricalEnvironmentConfigAndAcceptDatabaseConfig() {
        HistoricalSnapshot snapshot = insertHistoricalEnvironmentSnapshot("capture");
        assertThatThrownBy(() -> resolver.captureForPayment(snapshot.config()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));

        Long privateKeyFileId = insertPrivateStorageFile(22301L, "private/payment/capture.pem", PRIVATE_KEY_PEM);
        Long publicKeyFileId = insertPrivateStorageFile(22302L, "private/payment/capture-public.pem", PUBLIC_KEY_PEM);
        insertDbConfig(22303L, "Capture Config", true, "capture_db", privateKeyFileId, publicKeyFileId);
        ResolvedPaymentConfig database = resolver.resolve();
        assertThat(resolver.captureForPayment(database)).isEqualTo(resolver.fingerprint(database));
    }

    @Test
    void encryptionFailsValidationWhenRootKeyIsMissing() {
        PaymentSecretCipher cipherWithoutKey =
                new AesGcmPaymentSecretCipher(legacyEncryptionProperties(""));

        assertThatThrownBy(() -> cipherWithoutKey.encrypt("api_v3_secret_test"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private HistoricalSnapshot insertHistoricalEnvironmentSnapshot(String suffix) {
        ResolvedPaymentConfig config = new ResolvedPaymentConfig(
                PaymentConfigSource.HISTORICAL_SNAPSHOT,
                null,
                "Historical Environment " + suffix,
                true,
                "wx_" + suffix,
                "mch_" + suffix,
                "serial_" + suffix,
                "api_v3_" + suffix,
                PRIVATE_KEY_PEM.replace("test-private-key-material", "private-key-" + suffix),
                "https://" + suffix + ".test/wxpay/pay/notify",
                "https://" + suffix + ".test/wxpay/refund/notify",
                PaymentVerifyMode.PUBLIC_KEY,
                "public_" + suffix,
                PUBLIC_KEY_PEM.replace("test-public-key-material", "public-key-" + suffix),
                null,
                null,
                null
        );
        String fingerprint = resolver.fingerprint(config);
        PaymentSecretCipher.EncryptedSecret apiV3Key = secretCipher.encrypt(
                snapshotContext(fingerprint, "api-v3-key"), config.apiV3Key());
        PaymentSecretCipher.EncryptedSecret privateKey = secretCipher.encrypt(
                snapshotContext(fingerprint, "private-key-pem"), config.privateKeyPem());
        PaymentSecretCipher.EncryptedSecret publicKey = secretCipher.encrypt(
                snapshotContext(fingerprint, "wechat-public-key-pem"), config.wechatPublicKeyPem());
        assertThat(privateKey.version()).isEqualTo(apiV3Key.version());
        assertThat(publicKey.keyId()).isEqualTo(apiV3Key.keyId());
        jdbcClient.sql("""
                        insert into payment_config_snapshot
                            (fingerprint, config_source, config_name, app_id, mch_id,
                             merchant_serial_no, api_v3_key_ciphertext, private_key_pem_ciphertext,
                             notify_url, refund_notify_url, verify_mode, wechat_public_key_id,
                             wechat_public_key_pem_ciphertext, secret_cipher_version, secret_key_id)
                        values
                            (:fingerprint, 'ENV', :configName, :appId, :mchId, :merchantSerialNo,
                             :apiV3Key, :privateKey, :notifyUrl, :refundNotifyUrl, 'PUBLIC_KEY',
                             :publicKeyId, :publicKey, :cipherVersion, :keyId)
                        """)
                .param("fingerprint", fingerprint)
                .param("configName", config.configName())
                .param("appId", config.appId())
                .param("mchId", config.mchId())
                .param("merchantSerialNo", config.merchantSerialNo())
                .param("apiV3Key", apiV3Key.ciphertext())
                .param("privateKey", privateKey.ciphertext())
                .param("notifyUrl", config.notifyUrl())
                .param("refundNotifyUrl", config.refundNotifyUrl())
                .param("publicKeyId", config.wechatPublicKeyId())
                .param("publicKey", publicKey.ciphertext())
                .param("cipherVersion", apiV3Key.version())
                .param("keyId", apiV3Key.keyId())
                .update();
        return new HistoricalSnapshot(config, fingerprint, apiV3Key.ciphertext());
    }

    private PaymentSecretCipher.SecretContext snapshotContext(String fingerprint, String fieldName) {
        return new PaymentSecretCipher.SecretContext("payment-config-snapshot", fingerprint, fieldName);
    }

    private Long insertPrivateStorageFile(Long id, String objectKey, String content) {
        String uniqueObjectKey = "test/" + UUID.randomUUID() + "/" + objectKey;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(uniqueObjectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, status,
                             uploaded_by_type, uploaded_by_id)
                        values
                            (:id, 'SECRET', 'DOCUMENT', 'PRIVATE', 'TENCENT_COS', '', :objectKey,
                             'key.pem', 'text/plain', 'pem', :sizeBytes, '', 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("id", id)
                .param("objectKey", uniqueObjectKey)
                .param("sizeBytes", bytes.length)
                .update();
        return id;
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
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (:id, :configName, :appId, :mchId, :merchantSerialNo, :ciphertext, '', '',
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

    private SecretEncryptionProperties legacyEncryptionProperties(String legacyKey) {
        return new SecretEncryptionProperties(1, "", "", legacyKey, false, Duration.ofMinutes(1), 50);
    }

    private record HistoricalSnapshot(
            ResolvedPaymentConfig config,
            String fingerprint,
            String apiV3KeyCiphertext
    ) {
    }
}
