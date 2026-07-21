package org.muybaby.shopserver.location.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.location.dto.AdminAmapConfigRequest;
import org.muybaby.shopserver.location.dto.AdminAmapConfigResponse;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AmapRuntimeConfigService {

    private static final long SETTING_ID = 1L;
    private static final int MAX_KEY_LENGTH = 128;

    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;

    public AmapRuntimeConfigService(JdbcClient jdbcClient, PaymentSecretCipher secretCipher) {
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
    }

    public ResolvedAmapConfig effective() {
        return persistedRow()
                .map(row -> new ResolvedAmapConfig(row.enabled(), decryptKey(row)))
                .orElseGet(() -> new ResolvedAmapConfig(false, ""));
    }

    public AdminAmapConfigResponse current() {
        Optional<AmapSettingRow> row = persistedRow();
        if (row.isEmpty()) {
            return new AdminAmapConfigResponse(false, false, "", null);
        }
        String key = decryptKey(row.get());
        return new AdminAmapConfigResponse(
                row.get().enabled(),
                StringUtils.hasText(key),
                mask(key),
                row.get().updatedAt()
        );
    }

    @Transactional
    public AdminAmapConfigResponse update(AdminAmapConfigRequest request) {
        if (request == null || request.enabled() == null) {
            throw validationFailure();
        }

        Optional<AmapSettingRow> existing = persistedRow();
        String submittedKey = normalizeKey(request.miniProgramKey());
        String resolvedKey = StringUtils.hasText(submittedKey)
                ? submittedKey
                : existing.map(this::decryptKey).orElse("");
        if (request.enabled() && !StringUtils.hasText(resolvedKey)) {
            throw validationFailure();
        }

        PaymentSecretCipher.EncryptedSecret encrypted = StringUtils.hasText(resolvedKey)
                ? secretCipher.encrypt(secretContext(), resolvedKey)
                : null;
        String ciphertext = encrypted == null ? "" : encrypted.ciphertext();
        int cipherVersion = encrypted == null ? 1 : encrypted.version();
        String keyId = encrypted == null ? "" : encrypted.keyId();

        int updatedRows = jdbcClient.sql("""
                        update amap_runtime_setting
                        set enabled = :enabled,
                            mini_program_key_ciphertext = :ciphertext,
                            secret_cipher_version = :cipherVersion,
                            secret_key_id = :keyId,
                            secret_revision = secret_revision + 1,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("enabled", request.enabled())
                .param("ciphertext", ciphertext)
                .param("cipherVersion", cipherVersion)
                .param("keyId", keyId)
                .param("id", SETTING_ID)
                .update();
        if (updatedRows == 0) {
            jdbcClient.sql("""
                            insert into amap_runtime_setting
                                (id, enabled, mini_program_key_ciphertext, secret_cipher_version,
                                 secret_key_id, secret_revision)
                            values
                                (:id, :enabled, :ciphertext, :cipherVersion, :keyId, 1)
                            """)
                    .param("id", SETTING_ID)
                    .param("enabled", request.enabled())
                    .param("ciphertext", ciphertext)
                    .param("cipherVersion", cipherVersion)
                    .param("keyId", keyId)
                    .update();
        }
        return current();
    }

    private Optional<AmapSettingRow> persistedRow() {
        return jdbcClient.sql("""
                        select enabled, mini_program_key_ciphertext, secret_cipher_version,
                               secret_key_id, updated_at
                        from amap_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::mapRow)
                .optional();
    }

    private String decryptKey(AmapSettingRow row) {
        if (!StringUtils.hasText(row.ciphertext())) {
            return "";
        }
        PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(secretContext(), row.ciphertext());
        if (decrypted.version() != row.cipherVersion()
                || !decrypted.keyId().equals(row.keyId() == null ? "" : row.keyId())) {
            throw validationFailure();
        }
        return decrypted.plaintext();
    }

    private String normalizeKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_KEY_LENGTH) {
            throw validationFailure();
        }
        return normalized;
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 4) + "******" + value.substring(value.length() - 4);
    }

    private PaymentSecretCipher.SecretContext secretContext() {
        return new PaymentSecretCipher.SecretContext(
                "amap-runtime-setting", Long.toString(SETTING_ID), "mini-program-key");
    }

    private AmapSettingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AmapSettingRow(
                rs.getBoolean("enabled"),
                rs.getString("mini_program_key_ciphertext"),
                rs.getInt("secret_cipher_version"),
                rs.getString("secret_key_id"),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record AmapSettingRow(
            boolean enabled,
            String ciphertext,
            int cipherVersion,
            String keyId,
            LocalDateTime updatedAt
    ) {
    }
}
