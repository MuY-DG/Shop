package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.auth.dto.AdminRegistrationAvailabilityResponse;
import org.muybaby.shopserver.auth.dto.AdminRegistrationRequest;
import org.muybaby.shopserver.auth.dto.AdminRegistrationStatusResponse;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
public class AdminRegistrationService {

    private static final long REGISTRATION_SETTING_ID = 1L;
    private static final String GUEST_ROLE_CODE = "R_GUEST";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public AdminRegistrationService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PasswordEncoder passwordEncoder
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public AdminRegistrationAvailabilityResponse availability() {
        boolean enabled;
        try {
            enabled = jdbcClient.sql("""
                            SELECT enabled
                            FROM admin_registration_setting
                            WHERE id = :settingId
                            """)
                    .param("settingId", REGISTRATION_SETTING_ID)
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
        } catch (DataAccessException ex) {
            enabled = false;
        }
        return new AdminRegistrationAvailabilityResponse(enabled);
    }

    public AdminRegistrationStatusResponse currentSetting() {
        return jdbcClient.sql("""
                        SELECT enabled, updated_at
                        FROM admin_registration_setting
                        WHERE id = :settingId
                        """)
                .param("settingId", REGISTRATION_SETTING_ID)
                .query((rs, rowNum) -> new AdminRegistrationStatusResponse(
                        rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ADMIN_REGISTRATION_SETTING_UNAVAILABLE));
    }

    @Transactional
    public AdminRegistrationStatusResponse updateSetting(Long operatorUserId, boolean enabled) {
        lockSetting();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                        UPDATE admin_registration_setting
                        SET enabled = :enabled,
                            updated_by_admin_user_id = :operatorUserId,
                            updated_at = :now
                        WHERE id = :settingId
                        """)
                .param("enabled", enabled)
                .param("operatorUserId", operatorUserId)
                .param("now", now)
                .param("settingId", REGISTRATION_SETTING_ID)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_REGISTRATION_SETTING_UNAVAILABLE);
        }
        return new AdminRegistrationStatusResponse(enabled, now);
    }

    @Transactional
    public Long register(AdminRegistrationRequest request) {
        if (!lockSetting()) {
            throw new BusinessException(ErrorCode.ADMIN_REGISTRATION_DISABLED);
        }

        Long guestRoleId = jdbcClient.sql("""
                        SELECT id
                        FROM admin_role
                        WHERE code = :roleCode
                          AND enabled = TRUE
                        """)
                .param("roleCode", GUEST_ROLE_CODE)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_ROLE_UNAVAILABLE));

        String username = request.username().strip();
        String normalizedUsername = normalizeUsername(username);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            INSERT INTO admin_user (
                                username, username_normalized, password_hash, display_name,
                                email, avatar, status, max_sessions, created_at, updated_at
                            ) VALUES (
                                :username, :usernameNormalized, :passwordHash, :displayName,
                                '', '', 'ENABLED', 1, :now, :now
                            )
                            """,
                    new MapSqlParameterSource()
                            .addValue("username", username)
                            .addValue("usernameNormalized", normalizedUsername)
                            .addValue("passwordHash", passwordEncoder.encode(request.password()))
                            .addValue("displayName", username)
                            .addValue("now", now),
                    keyHolder,
                    new String[]{"id"});
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ADMIN_USERNAME_CONFLICT);
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        long userId = key.longValue();
        jdbcClient.sql("""
                        INSERT INTO admin_user_role (user_id, role_id)
                        VALUES (:userId, :roleId)
                        """)
                .param("userId", userId)
                .param("roleId", guestRoleId)
                .update();
        return userId;
    }

    private boolean lockSetting() {
        return jdbcClient.sql("""
                        SELECT enabled
                        FROM admin_registration_setting
                        WHERE id = :settingId
                        FOR UPDATE
                        """)
                .param("settingId", REGISTRATION_SETTING_ID)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ADMIN_REGISTRATION_SETTING_UNAVAILABLE));
    }

    static String normalizeUsername(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }
}
