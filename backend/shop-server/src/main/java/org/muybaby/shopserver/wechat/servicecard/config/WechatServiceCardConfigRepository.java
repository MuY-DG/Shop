package org.muybaby.shopserver.wechat.servicecard.config;

import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class WechatServiceCardConfigRepository {

    public static final long SETTING_ID = 1L;

    private final JdbcClient jdbcClient;

    public WechatServiceCardConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<WechatServiceCardConfigEntity> find() {
        return jdbcClient.sql("""
                        select id, account_template_record_id, fallback_product_image,
                               allowed_image_hosts, prefer_order_snapshot_images,
                               callback_enabled, callback_token_ciphertext,
                               callback_token_cipher_version, callback_token_key_id,
                               callback_token_secret_revision, callback_aes_key_ciphertext,
                               callback_aes_key_cipher_version, callback_aes_key_key_id,
                               callback_aes_key_secret_revision, revision,
                               created_by, updated_by, callback_token_reencrypted_at,
                               callback_aes_key_reencrypted_at, created_at, updated_at
                        from wechat_service_card_config
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::map)
                .optional();
    }

    public boolean insert(
            StoredConfig config,
            Long operatorId
    ) {
        try {
            return jdbcClient.sql("""
                            insert into wechat_service_card_config (
                                id, account_template_record_id, fallback_product_image,
                                allowed_image_hosts, prefer_order_snapshot_images,
                                callback_enabled, callback_token_ciphertext,
                                callback_token_cipher_version, callback_token_key_id,
                                callback_token_secret_revision, callback_aes_key_ciphertext,
                                callback_aes_key_cipher_version, callback_aes_key_key_id,
                                callback_aes_key_secret_revision, revision,
                                created_by, updated_by, created_at, updated_at
                            ) values (
                                :id, :templateId, :fallbackImage,
                                :allowedHosts, :preferSnapshot,
                                :callbackEnabled, :tokenCiphertext,
                                :tokenCipherVersion, :tokenKeyId,
                                :tokenRevision, :aesCiphertext,
                                :aesCipherVersion, :aesKeyId,
                                :aesRevision, 1,
                                :operatorId, :operatorId, current_timestamp, current_timestamp
                            )
                            """)
                    .param("id", SETTING_ID)
                    .param("templateId", config.accountTemplateRecordId())
                    .param("fallbackImage", config.fallbackProductImage())
                    .param("allowedHosts", config.allowedImageHosts())
                    .param("preferSnapshot", config.preferOrderSnapshotImages())
                    .param("callbackEnabled", config.callbackEnabled())
                    .param("tokenCiphertext", ciphertext(config.callbackToken()))
                    .param("tokenCipherVersion", cipherVersion(config.callbackToken()))
                    .param("tokenKeyId", keyId(config.callbackToken()))
                    .param("tokenRevision", config.callbackToken() == null ? 0L : 1L)
                    .param("aesCiphertext", ciphertext(config.callbackAesKey()))
                    .param("aesCipherVersion", cipherVersion(config.callbackAesKey()))
                    .param("aesKeyId", keyId(config.callbackAesKey()))
                    .param("aesRevision", config.callbackAesKey() == null ? 0L : 1L)
                    .param("operatorId", operatorId)
                    .update() == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public boolean update(
            long expectedRevision,
            long expectedTokenSecretRevision,
            long expectedAesSecretRevision,
            StoredConfig config,
            boolean tokenChanged,
            boolean aesKeyChanged,
            Long operatorId
    ) {
        return jdbcClient.sql("""
                        update wechat_service_card_config
                        set account_template_record_id = :templateId,
                            fallback_product_image = :fallbackImage,
                            allowed_image_hosts = :allowedHosts,
                            prefer_order_snapshot_images = :preferSnapshot,
                            callback_enabled = :callbackEnabled,
                            callback_token_ciphertext = case when :tokenChanged = true
                                then :tokenCiphertext else callback_token_ciphertext end,
                            callback_token_cipher_version = case when :tokenChanged = true
                                then :tokenCipherVersion else callback_token_cipher_version end,
                            callback_token_key_id = case when :tokenChanged = true
                                then :tokenKeyId else callback_token_key_id end,
                            callback_token_secret_revision = case when :tokenChanged = true
                                then callback_token_secret_revision + 1
                                else callback_token_secret_revision end,
                            callback_aes_key_ciphertext = case when :aesChanged = true
                                then :aesCiphertext else callback_aes_key_ciphertext end,
                            callback_aes_key_cipher_version = case when :aesChanged = true
                                then :aesCipherVersion else callback_aes_key_cipher_version end,
                            callback_aes_key_key_id = case when :aesChanged = true
                                then :aesKeyId else callback_aes_key_key_id end,
                            callback_aes_key_secret_revision = case when :aesChanged = true
                                then callback_aes_key_secret_revision + 1
                                else callback_aes_key_secret_revision end,
                            revision = revision + 1,
                            callback_token_reencrypted_at = case when :tokenChanged = true
                                then null else callback_token_reencrypted_at end,
                            callback_aes_key_reencrypted_at = case when :aesChanged = true
                                then null else callback_aes_key_reencrypted_at end,
                            updated_by = :operatorId,
                            updated_at = current_timestamp
                        where id = :id and revision = :expectedRevision
                          and callback_token_secret_revision = :expectedTokenSecretRevision
                          and callback_aes_key_secret_revision = :expectedAesSecretRevision
                        """)
                .param("templateId", config.accountTemplateRecordId())
                .param("fallbackImage", config.fallbackProductImage())
                .param("allowedHosts", config.allowedImageHosts())
                .param("preferSnapshot", config.preferOrderSnapshotImages())
                .param("callbackEnabled", config.callbackEnabled())
                .param("tokenCiphertext", ciphertext(config.callbackToken()))
                .param("tokenCipherVersion", cipherVersion(config.callbackToken()))
                .param("tokenKeyId", keyId(config.callbackToken()))
                .param("tokenChanged", tokenChanged)
                .param("aesCiphertext", ciphertext(config.callbackAesKey()))
                .param("aesCipherVersion", cipherVersion(config.callbackAesKey()))
                .param("aesKeyId", keyId(config.callbackAesKey()))
                .param("aesChanged", aesKeyChanged)
                .param("operatorId", operatorId)
                .param("id", SETTING_ID)
                .param("expectedRevision", expectedRevision)
                .param("expectedTokenSecretRevision", expectedTokenSecretRevision)
                .param("expectedAesSecretRevision", expectedAesSecretRevision)
                .update() == 1;
    }

    public boolean rotateCallbackToken(
            long expectedRevision,
            long expectedSecretRevision,
            PaymentSecretCipher.EncryptedSecret encrypted
    ) {
        return jdbcClient.sql("""
                        update wechat_service_card_config
                        set callback_token_ciphertext = :ciphertext,
                            callback_token_cipher_version = :cipherVersion,
                            callback_token_key_id = :keyId,
                            callback_token_secret_revision = callback_token_secret_revision + 1,
                            callback_token_reencrypted_at = current_timestamp
                        where id = :id and revision = :revision
                          and callback_token_secret_revision = :secretRevision
                        """)
                .param("ciphertext", encrypted.ciphertext())
                .param("cipherVersion", encrypted.version())
                .param("keyId", encrypted.keyId())
                .param("id", SETTING_ID)
                .param("revision", expectedRevision)
                .param("secretRevision", expectedSecretRevision)
                .update() == 1;
    }

    public boolean rotateCallbackAesKey(
            long expectedRevision,
            long expectedSecretRevision,
            PaymentSecretCipher.EncryptedSecret encrypted
    ) {
        return jdbcClient.sql("""
                        update wechat_service_card_config
                        set callback_aes_key_ciphertext = :ciphertext,
                            callback_aes_key_cipher_version = :cipherVersion,
                            callback_aes_key_key_id = :keyId,
                            callback_aes_key_secret_revision = callback_aes_key_secret_revision + 1,
                            callback_aes_key_reencrypted_at = current_timestamp
                        where id = :id and revision = :revision
                          and callback_aes_key_secret_revision = :secretRevision
                        """)
                .param("ciphertext", encrypted.ciphertext())
                .param("cipherVersion", encrypted.version())
                .param("keyId", encrypted.keyId())
                .param("id", SETTING_ID)
                .param("revision", expectedRevision)
                .param("secretRevision", expectedSecretRevision)
                .update() == 1;
    }

    public void appendAudit(
            long revision,
            String action,
            AuditState before,
            AuditState after,
            Long operatorId
    ) {
        int inserted = jdbcClient.sql("""
                        insert into wechat_service_card_config_audit (
                            revision, action_type,
                            template_record_id_before, template_record_id_after,
                            fallback_image_before, fallback_image_after,
                            allowed_hosts_before, allowed_hosts_after,
                            prefer_snapshot_before, prefer_snapshot_after,
                            callback_enabled_before, callback_enabled_after,
                            callback_token_configured_before, callback_token_configured_after,
                            callback_aes_key_configured_before, callback_aes_key_configured_after,
                            operator_id, created_at
                        ) values (
                            :revision, :action,
                            :templateBefore, :templateAfter,
                            :imageBefore, :imageAfter,
                            :hostsBefore, :hostsAfter,
                            :preferBefore, :preferAfter,
                            :callbackBefore, :callbackAfter,
                            :tokenBefore, :tokenAfter,
                            :aesBefore, :aesAfter,
                            :operatorId, current_timestamp
                        )
                        """)
                .param("revision", revision)
                .param("action", action)
                .param("templateBefore", before.accountTemplateRecordId())
                .param("templateAfter", after.accountTemplateRecordId())
                .param("imageBefore", before.fallbackProductImage())
                .param("imageAfter", after.fallbackProductImage())
                .param("hostsBefore", before.allowedImageHosts())
                .param("hostsAfter", after.allowedImageHosts())
                .param("preferBefore", before.preferOrderSnapshotImages())
                .param("preferAfter", after.preferOrderSnapshotImages())
                .param("callbackBefore", before.callbackEnabled())
                .param("callbackAfter", after.callbackEnabled())
                .param("tokenBefore", before.callbackTokenConfigured())
                .param("tokenAfter", after.callbackTokenConfigured())
                .param("aesBefore", before.callbackAesKeyConfigured())
                .param("aesAfter", after.callbackAesKeyConfigured())
                .param("operatorId", operatorId)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("WeChat service-card config audit insert failed");
        }
    }

    private WechatServiceCardConfigEntity map(ResultSet rs, int rowNum) throws SQLException {
        return new WechatServiceCardConfigEntity(
                rs.getLong("id"),
                rs.getString("account_template_record_id"),
                rs.getString("fallback_product_image"),
                rs.getString("allowed_image_hosts"),
                rs.getBoolean("prefer_order_snapshot_images"),
                rs.getBoolean("callback_enabled"),
                rs.getString("callback_token_ciphertext"),
                rs.getObject("callback_token_cipher_version", Integer.class),
                rs.getString("callback_token_key_id"),
                rs.getLong("callback_token_secret_revision"),
                rs.getString("callback_aes_key_ciphertext"),
                rs.getObject("callback_aes_key_cipher_version", Integer.class),
                rs.getString("callback_aes_key_key_id"),
                rs.getLong("callback_aes_key_secret_revision"),
                rs.getLong("revision"),
                rs.getObject("created_by", Long.class),
                rs.getObject("updated_by", Long.class),
                rs.getObject("callback_token_reencrypted_at", LocalDateTime.class),
                rs.getObject("callback_aes_key_reencrypted_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private String ciphertext(PaymentSecretCipher.EncryptedSecret secret) {
        return secret == null ? null : secret.ciphertext();
    }

    private Integer cipherVersion(PaymentSecretCipher.EncryptedSecret secret) {
        return secret == null ? null : secret.version();
    }

    private String keyId(PaymentSecretCipher.EncryptedSecret secret) {
        return secret == null ? null : secret.keyId();
    }

    public record StoredConfig(
            String accountTemplateRecordId,
            String fallbackProductImage,
            String allowedImageHosts,
            boolean preferOrderSnapshotImages,
            boolean callbackEnabled,
            PaymentSecretCipher.EncryptedSecret callbackToken,
            PaymentSecretCipher.EncryptedSecret callbackAesKey
    ) {
    }

    public record AuditState(
            String accountTemplateRecordId,
            String fallbackProductImage,
            String allowedImageHosts,
            boolean preferOrderSnapshotImages,
            boolean callbackEnabled,
            boolean callbackTokenConfigured,
            boolean callbackAesKeyConfigured
    ) {
        public static AuditState empty() {
            return new AuditState("", "", "", false, false, false, false);
        }
    }
}
