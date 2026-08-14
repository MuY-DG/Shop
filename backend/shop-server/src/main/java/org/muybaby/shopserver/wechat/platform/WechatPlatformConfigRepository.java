package org.muybaby.shopserver.wechat.platform;

import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class WechatPlatformConfigRepository {

    public static final long SETTING_ID = 1L;

    private final JdbcClient jdbcClient;

    public WechatPlatformConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<WechatPlatformConfigEntity> find() {
        return jdbcClient.sql("""
                        select id, app_id, app_secret_ciphertext,
                               secret_cipher_version, secret_key_id, secret_revision,
                               revision, imported_from_env_at, created_by, updated_by,
                               secret_reencrypted_at, created_at, updated_at
                        from wechat_platform_config
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::map)
                .optional();
    }

    public boolean insert(
            String appId,
            PaymentSecretCipher.EncryptedSecret encryptedSecret,
            Long operatorId,
            boolean importedFromEnvironment
    ) {
        try {
            return jdbcClient.sql("""
                            insert into wechat_platform_config (
                                id, app_id, app_secret_ciphertext,
                                secret_cipher_version, secret_key_id,
                                secret_revision, revision, imported_from_env_at,
                                created_by, updated_by, created_at, updated_at
                            ) values (
                                :id, :appId, :ciphertext,
                                :cipherVersion, :keyId,
                                1, 1, :importedAt,
                                :operatorId, :operatorId, current_timestamp, current_timestamp
                            )
                            """)
                    .param("id", SETTING_ID)
                    .param("appId", appId)
                    .param("ciphertext", encryptedSecret.ciphertext())
                    .param("cipherVersion", encryptedSecret.version())
                    .param("keyId", encryptedSecret.keyId())
                    .param("importedAt", importedFromEnvironment ? LocalDateTime.now() : null)
                    .param("operatorId", operatorId)
                    .update() == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public boolean update(
            long expectedRevision,
            String appId,
            PaymentSecretCipher.EncryptedSecret encryptedSecret,
            Long operatorId
    ) {
        return jdbcClient.sql("""
                        update wechat_platform_config
                        set app_id = :appId,
                            app_secret_ciphertext = :ciphertext,
                            secret_cipher_version = :cipherVersion,
                            secret_key_id = :keyId,
                            secret_revision = secret_revision + 1,
                            revision = revision + 1,
                            secret_reencrypted_at = null,
                            updated_by = :operatorId,
                            updated_at = current_timestamp
                        where id = :id and revision = :expectedRevision
                        """)
                .param("appId", appId)
                .param("ciphertext", encryptedSecret.ciphertext())
                .param("cipherVersion", encryptedSecret.version())
                .param("keyId", encryptedSecret.keyId())
                .param("operatorId", operatorId)
                .param("id", SETTING_ID)
                .param("expectedRevision", expectedRevision)
                .update() == 1;
    }

    public boolean rotate(
            long expectedRevision,
            long expectedSecretRevision,
            PaymentSecretCipher.EncryptedSecret encryptedSecret
    ) {
        return jdbcClient.sql("""
                        update wechat_platform_config
                        set app_secret_ciphertext = :ciphertext,
                            secret_cipher_version = :cipherVersion,
                            secret_key_id = :keyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = current_timestamp
                        where id = :id
                          and revision = :expectedRevision
                          and secret_revision = :expectedSecretRevision
                        """)
                .param("ciphertext", encryptedSecret.ciphertext())
                .param("cipherVersion", encryptedSecret.version())
                .param("keyId", encryptedSecret.keyId())
                .param("id", SETTING_ID)
                .param("expectedRevision", expectedRevision)
                .param("expectedSecretRevision", expectedSecretRevision)
                .update() == 1;
    }

    private WechatPlatformConfigEntity map(ResultSet rs, int rowNum) throws SQLException {
        return new WechatPlatformConfigEntity(
                rs.getLong("id"),
                rs.getString("app_id"),
                rs.getString("app_secret_ciphertext"),
                rs.getInt("secret_cipher_version"),
                rs.getString("secret_key_id"),
                rs.getLong("secret_revision"),
                rs.getLong("revision"),
                rs.getObject("imported_from_env_at", LocalDateTime.class),
                rs.getObject("created_by", Long.class),
                rs.getObject("updated_by", Long.class),
                rs.getObject("secret_reencrypted_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }
}
