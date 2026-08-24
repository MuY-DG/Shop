package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "shop.secret-encryption.active-key-id=new-2026",
        "shop.secret-encryption.key-ring="
                + "old-2025=base64:b2xkLWtleS1tYXRlcmlhbC0zMi1ieXRlcy0wMDAwMDA=;"
                + "new-2026=base64:bmV3LWtleS1tYXRlcmlhbC0zMi1ieXRlcy0wMDAwMDA=",
        "shop.secret-encryption.rotation-enabled=false",
        "shop.secret-encryption.rotation-batch-size=10"
})
@ActiveProfiles("test")
class PaymentSecretRotationServiceTest {

    private static final long CONFIG_ID = 93001L;
    private static final String ACTIVE_KEY_ID = "new-2026";
    private static final String API_V3_KEY = "legacy-api-v3-key";
    private static final String PRIVATE_KEY = "legacy-private-key-pem";
    private static final String PUBLIC_KEY = "legacy-public-key-pem";
    private static final String COS_SECRET_ID = "legacy-cos-secret-id";
    private static final String COS_SECRET_KEY = "legacy-cos-secret-key";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SecretEncryptionProperties encryptionProperties;

    @Autowired
    private PaymentSecretCipher activeCipher;

    @Autowired
    private PaymentConfigResolver resolver;

    @Autowired
    private PaymentSecretRotationService rotationService;

    @Autowired
    private StorageRuntimeConfigService storageRuntimeConfigService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private PaymentSecretCipher oldCipher;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from payment_config where id in (:id, :damagedId)")
                .param("id", CONFIG_ID)
                .param("damagedId", CONFIG_ID - 1)
                .update();
        jdbcClient.sql("delete from storage_runtime_setting").update();
        resetCheckpoint("payment-config", "0");
        resetCheckpoint("storage-runtime-setting", "0");
        oldCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        "old-2025",
                        encryptionProperties.keyRing(),
                        false,
                        Duration.ofMinutes(1),
                        10));
        seedOldKeyPaymentConfig();
        seedOldKeyStorageConfig();
    }

    @Test
    void rotatesAllSupportedSecretDomainsAndIsIdempotent() {
        assertThat(rotationService.rotateBatch()).isEqualTo(2);

        SecretEnvelope paymentEnvelope = jdbcClient.sql("""
                        select api_v3_key_ciphertext as ciphertext,
                               secret_cipher_version, secret_key_id
                        from payment_config
                        where id = :id
                        """)
                .param("id", CONFIG_ID)
                .query((rs, rowNum) -> new SecretEnvelope(
                        rs.getString("ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")))
                .single();
        assertActiveEnvelope(paymentEnvelope);
        assertThat(activeCipher.decrypt(
                PaymentConfigResolver.apiV3KeyContext(CONFIG_ID), paymentEnvelope.ciphertext()).plaintext())
                .isEqualTo(API_V3_KEY);
        String rotatedPrivateKey = jdbcClient.sql("""
                        select private_key_pem_ciphertext from payment_config where id = :id
                        """)
                .param("id", CONFIG_ID)
                .query(String.class)
                .single();
        String rotatedPublicKey = jdbcClient.sql("""
                        select wechat_public_key_pem_ciphertext from payment_config where id = :id
                        """)
                .param("id", CONFIG_ID)
                .query(String.class)
                .single();
        PaymentSecretCipher.DecryptedSecret decryptedPrivateKey = activeCipher.decrypt(
                PaymentConfigResolver.privateKeyPemContext(CONFIG_ID), rotatedPrivateKey);
        PaymentSecretCipher.DecryptedSecret decryptedPublicKey = activeCipher.decrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(CONFIG_ID), rotatedPublicKey);
        assertThat(decryptedPrivateKey.plaintext()).isEqualTo(PRIVATE_KEY);
        assertThat(decryptedPublicKey.plaintext()).isEqualTo(PUBLIC_KEY);
        assertThat(decryptedPrivateKey.version()).isEqualTo(2);
        assertThat(decryptedPublicKey.version()).isEqualTo(2);
        assertThat(decryptedPrivateKey.keyId()).isEqualTo(ACTIVE_KEY_ID);
        assertThat(decryptedPublicKey.keyId()).isEqualTo(ACTIVE_KEY_ID);

        ResolvedStorageConfig storage = storageRuntimeConfigService.effective();
        assertThat(storage.secretId()).isEqualTo(COS_SECRET_ID);
        assertThat(storage.secretKey()).isEqualTo(COS_SECRET_KEY);
        SecretEnvelope storageEnvelope = jdbcClient.sql("""
                        select cos_secret_id_ciphertext as ciphertext,
                               secret_cipher_version, secret_key_id
                        from storage_runtime_setting
                        where id = 1
                        """)
                .query((rs, rowNum) -> new SecretEnvelope(
                        rs.getString("ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")))
                .single();
        assertActiveEnvelope(storageEnvelope);

        assertThat(rotationService.rotateBatch()).isZero();
        assertThat(jdbcClient.sql("""
                        select api_v3_key_ciphertext
                        from payment_config
                        where id = :id
                        """)
                .param("id", CONFIG_ID)
                .query(String.class)
                .single()).isEqualTo(paymentEnvelope.ciphertext());
    }

    @Test
    void softDeletedHistoricalConfigRemainsResolvableAndParticipatesInKeyRotation() {
        jdbcClient.sql("""
                        update payment_config
                        set status = 'DELETED', enabled = false,
                            deleted_at = current_timestamp, deleted_by = 1
                        where id = :id
                        """)
                .param("id", CONFIG_ID)
                .update();

        assertThat(rotationService.rotateBatch()).isEqualTo(2);
        ResolvedPaymentConfig historical = resolver.resolveForPaymentConfigId(CONFIG_ID);
        assertThat(historical.apiV3Key()).isEqualTo(API_V3_KEY);
        assertThat(historical.privateKeyPem()).isEqualTo(PRIVATE_KEY);
        assertThat(historical.wechatPublicKeyPem()).isEqualTo(PUBLIC_KEY);
    }

    @Test
    void storageSecretReencryptionPreservesCustomDomainVerification() {
        String customOrigin = "https://rotation.example.test";
        String fingerprint = sha256(customOrigin + '\0' + "ap-guangzhou" + '\0'
                + "rotation-bucket-1250000000");
        jdbcClient.sql("""
                        update storage_runtime_setting
                        set cos_public_base_url = :customOrigin,
                            cos_custom_domain_verification_fingerprint = :fingerprint
                        where id = 1
                        """)
                .param("customOrigin", customOrigin)
                .param("fingerprint", fingerprint)
                .update();
        assertThat(storageRuntimeConfigService.effective().publicBaseUrl())
                .isEqualTo(customOrigin);

        assertThat(rotationService.rotateBatch()).isEqualTo(2);

        assertThat(storageRuntimeConfigService.effective().publicBaseUrl())
                .isEqualTo(customOrigin);
        assertThat(jdbcClient.sql("""
                        select cos_custom_domain_verification_fingerprint
                        from storage_runtime_setting
                        where id = 1
                        """)
                .query(String.class)
                .single()).isEqualTo(fingerprint);
    }

    @Test
    void damagedHeadCheckpointSurvivesServiceReconstructionAndDoesNotStarveLaterRow() {
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode, wechat_public_key_id,
                             notify_url, refund_notify_url, enabled, status,
                             secret_cipher_version, secret_key_id)
                        values
                            (:id, 'Damaged Rotation Config', 'damaged-app', 'damaged-mch',
                             'damaged-serial', 'v2:old-2025:broken:broken', '', '', 'PUBLIC_KEY', 'damaged-public-id',
                             'https://pay.example.test/wxpay/pay/notify',
                             'https://pay.example.test/wxpay/refund/notify', false, 'ACTIVE', 2, 'old-2025')
                        """)
                .param("id", CONFIG_ID - 1)
                .update();
        PaymentSecretRotationService firstProcess = newRotationService(1);

        assertThat(firstProcess.rotateBatch()).isEqualTo(1);
        assertThat(checkpointCursor("payment-config"))
                .isEqualTo(Long.toString(CONFIG_ID - 1));

        PaymentSecretRotationService restartedProcess = newRotationService(1);
        assertThat(restartedProcess.rotateBatch()).isEqualTo(1);

        SecretEnvelope laterEnvelope = jdbcClient.sql("""
                        select api_v3_key_ciphertext as ciphertext,
                               secret_cipher_version, secret_key_id
                        from payment_config
                        where id = :id
                        """)
                .param("id", CONFIG_ID)
                .query((rs, rowNum) -> new SecretEnvelope(
                        rs.getString("ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")))
                .single();
        assertActiveEnvelope(laterEnvelope);
    }

    private PaymentSecretRotationService newRotationService(int batchSize) {
        return new PaymentSecretRotationService(
                jdbcClient,
                activeCipher,
                new SecretEncryptionProperties(
                        encryptionProperties.activeKeyId(),
                        encryptionProperties.keyRing(),
                        false,
                        Duration.ofMinutes(1),
                        batchSize),
                transactionManager);
    }

    private void resetCheckpoint(String checkpointName, String cursorValue) {
        jdbcClient.sql("""
                        update payment_secret_rotation_checkpoint
                        set cursor_value = :cursorValue,
                            scan_epoch = 0
                        where checkpoint_name = :checkpointName
                        """)
                .param("cursorValue", cursorValue)
                .param("checkpointName", checkpointName)
                .update();
    }

    private String checkpointCursor(String checkpointName) {
        return jdbcClient.sql("""
                        select cursor_value
                        from payment_secret_rotation_checkpoint
                        where checkpoint_name = :checkpointName
                        """)
                .param("checkpointName", checkpointName)
                .query(String.class)
                .single();
    }

    private void seedOldKeyPaymentConfig() {
        PaymentSecretCipher.EncryptedSecret apiV3Key = oldCipher.encrypt(
                PaymentConfigResolver.apiV3KeyContext(CONFIG_ID), API_V3_KEY);
        PaymentSecretCipher.EncryptedSecret privateKey = oldCipher.encrypt(
                PaymentConfigResolver.privateKeyPemContext(CONFIG_ID), PRIVATE_KEY);
        PaymentSecretCipher.EncryptedSecret publicKey = oldCipher.encrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(CONFIG_ID), PUBLIC_KEY);
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode, wechat_public_key_id,
                             notify_url, refund_notify_url, enabled, status,
                             secret_cipher_version, secret_key_id)
                        values
                            (:id, 'Rotation DB Config', 'rotation-app', 'rotation-mch',
                             'rotation-serial', :ciphertext, :privateKey, :publicKey,
                             'PUBLIC_KEY', 'rotation-public-id',
                             'https://pay.example.test/wxpay/pay/notify',
                             'https://pay.example.test/wxpay/refund/notify', false, 'ACTIVE',
                             :cipherVersion, :keyId)
                        """)
                .param("id", CONFIG_ID)
                .param("ciphertext", apiV3Key.ciphertext())
                .param("privateKey", privateKey.ciphertext())
                .param("publicKey", publicKey.ciphertext())
                .param("cipherVersion", apiV3Key.version())
                .param("keyId", apiV3Key.keyId())
                .update();
    }

    private void seedOldKeyStorageConfig() {
        PaymentSecretCipher.EncryptedSecret secretId = oldCipher.encrypt(
                StorageRuntimeConfigService.secretContext("cos-secret-id"), COS_SECRET_ID);
        PaymentSecretCipher.EncryptedSecret secretKey = oldCipher.encrypt(
                StorageRuntimeConfigService.secretContext("cos-secret-key"), COS_SECRET_KEY);
        jdbcClient.sql("""
                        insert into storage_runtime_setting
                            (id, cos_public_base_url, cos_region, cos_bucket,
                             cos_secret_id_ciphertext, cos_secret_key_ciphertext,
                             secret_cipher_version, secret_key_id)
                        values
                            (1, 'https://rotation-bucket-1250000000.cos.ap-guangzhou.myqcloud.com',
                             'ap-guangzhou', 'rotation-bucket-1250000000',
                             :secretId, :secretKey, :cipherVersion, :keyId)
                        """)
                .param("secretId", secretId.ciphertext())
                .param("secretKey", secretKey.ciphertext())
                .param("cipherVersion", secretId.version())
                .param("keyId", secretId.keyId())
                .update();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void assertActiveEnvelope(SecretEnvelope envelope) {
        assertThat(envelope.version()).isEqualTo(2);
        assertThat(envelope.keyId()).isEqualTo("new-2026");
        assertThat(envelope.ciphertext()).startsWith("v2:new-2026:");
    }

    private record SecretEnvelope(String ciphertext, int version, String keyId) {
    }
}
