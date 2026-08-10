package org.muybaby.shopserver.user.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatPhoneInfo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AppUserService {

    private static final String ENABLED_STATUS = "ENABLED";

    private final JdbcClient jdbcClient;

    public AppUserService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AppUser upsertByOpenid(WechatCodeSession session) {
        Optional<AppUser> existingUser = findByOpenid(session.openid());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (existingUser.isPresent()) {
            AppUser user = requireEnabled(existingUser.get());
            jdbcClient.sql("""
                            UPDATE app_user
                            SET unionid = :unionid, last_login_at = :lastLoginAt, updated_at = :updatedAt
                            WHERE id = :id AND status = :status
                            """)
                    .param("unionid", session.unionid())
                    .param("lastLoginAt", now)
                    .param("updatedAt", now)
                    .param("id", user.id())
                    .param("status", ENABLED_STATUS)
                    .update();
            return findEnabledById(user.id()).orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        }

        long userId = IdWorker.getId();
        try {
            jdbcClient.sql("""
                            INSERT INTO app_user (id, openid, unionid, nickname, status, last_login_at)
                            VALUES (:id, :openid, :unionid, :nickname, :status, :lastLoginAt)
                            """)
                    .param("id", userId)
                    .param("openid", session.openid())
                    .param("unionid", session.unionid())
                    .param("nickname", defaultNickname(userId))
                    .param("status", ENABLED_STATUS)
                    .param("lastLoginAt", now)
                    .update();
        } catch (DuplicateKeyException ex) {
            AppUser existingRaceUser = findByOpenid(session.openid()).orElseThrow(() -> ex);
            return requireEnabled(existingRaceUser);
        }
        return findEnabledById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    public AppUser markPhoneAuthorized(Long userId, WechatPhoneInfo phoneInfo) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int updatedRows = jdbcClient.sql("""
                        UPDATE app_user
                        SET phone_number = :phoneNumber,
                            phone_country_code = :phoneCountryCode,
                            phone_authorized = TRUE,
                            phone_authorized_at = COALESCE(phone_authorized_at, :phoneAuthorizedAt),
                            updated_at = :updatedAt
                        WHERE id = :id AND status = :status
                        """)
                .param("phoneNumber", phoneInfo.phoneNumber())
                .param("phoneCountryCode", phoneInfo.countryCode())
                .param("phoneAuthorizedAt", now)
                .param("updatedAt", now)
                .param("id", userId)
                .param("status", ENABLED_STATUS)
                .update();

        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return findEnabledById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    public AppUser requireEnabledUser(Long userId) {
        return findEnabledById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    public Optional<AppUserSessionState> findEnabledSessionState(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, auth_version
                        FROM app_user
                        WHERE id = :id AND status = :status
                        """)
                .param("id", userId)
                .param("status", ENABLED_STATUS)
                .query((rs, rowNum) -> new AppUserSessionState(
                        rs.getLong("id"),
                        rs.getLong("auth_version")
                ))
                .optional();
    }

    public AppUser requireEnabledUserForUpdate(Long userId) {
        return findEnabledByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    public AppUser requireUserForUpdate(Long userId) {
        return findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APP_USER_UNAVAILABLE));
    }

    public AppUser updateNickname(Long userId, String nickname) {
        String normalizedNickname = normalizeNickname(nickname);
        int updatedRows = jdbcClient.sql("""
                        UPDATE app_user
                        SET nickname = :nickname, updated_at = :updatedAt
                        WHERE id = :id AND status = :status
                        """)
                .param("nickname", normalizedNickname)
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("id", userId)
                .param("status", ENABLED_STATUS)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return findEnabledById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Transactional
    public AvatarReplacement replaceAvatar(Long userId, String avatarUrl) {
        if (!StringUtils.hasText(avatarUrl) || avatarUrl.length() > 1024) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        AppUser previousUser = findEnabledByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        int updatedRows = jdbcClient.sql("""
                        UPDATE app_user
                        SET avatar_url = :avatarUrl, updated_at = :updatedAt
                        WHERE id = :id AND status = :status
                        """)
                .param("avatarUrl", avatarUrl)
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("id", userId)
                .param("status", ENABLED_STATUS)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        AppUser updatedUser = findEnabledById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        return new AvatarReplacement(previousUser.avatarUrl(), updatedUser);
    }

    private Optional<AppUser> findByOpenid(String openid) {
        return jdbcClient.sql("""
                        SELECT id, openid, unionid, nickname, avatar_url, phone_number, phone_country_code, phone_authorized,
                               status, last_login_at, created_at, updated_at, auth_version, cancelled_at
                        FROM app_user
                        WHERE openid = :openid
                        """)
                .param("openid", openid)
                .query(this::mapRow)
                .optional();
    }

    private Optional<AppUser> findById(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, openid, unionid, nickname, avatar_url, phone_number, phone_country_code, phone_authorized,
                               status, last_login_at, created_at, updated_at, auth_version, cancelled_at
                        FROM app_user
                        WHERE id = :id
                        """)
                .param("id", userId)
                .query(this::mapRow)
                .optional();
    }

    private Optional<AppUser> findEnabledById(Long userId) {
        return findById(userId).filter(this::isEnabled);
    }

    private Optional<AppUser> findEnabledByIdForUpdate(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, openid, unionid, nickname, avatar_url, phone_number, phone_country_code, phone_authorized,
                               status, last_login_at, created_at, updated_at, auth_version, cancelled_at
                        FROM app_user
                        WHERE id = :id AND status = :status
                        FOR UPDATE
                        """)
                .param("id", userId)
                .param("status", ENABLED_STATUS)
                .query(this::mapRow)
                .optional();
    }

    private Optional<AppUser> findByIdForUpdate(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, openid, unionid, nickname, avatar_url, phone_number, phone_country_code, phone_authorized,
                               status, last_login_at, created_at, updated_at, auth_version, cancelled_at
                        FROM app_user
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", userId)
                .query(this::mapRow)
                .optional();
    }

    private AppUser requireEnabled(AppUser user) {
        if (!isEnabled(user)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return user;
    }

    public record AvatarReplacement(
            String previousAvatarUrl,
            AppUser user
    ) {
    }

    private boolean isEnabled(AppUser user) {
        return ENABLED_STATUS.equals(user.status());
    }

    private AppUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getLong("id"),
                rs.getString("openid"),
                rs.getString("unionid"),
                resolvedNickname(rs.getLong("id"), rs.getString("nickname")),
                rs.getString("avatar_url"),
                rs.getString("phone_number"),
                rs.getString("phone_country_code"),
                rs.getBoolean("phone_authorized"),
                rs.getString("status"),
                rs.getObject("last_login_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getLong("auth_version"),
                rs.getObject("cancelled_at", LocalDateTime.class)
        );
    }

    public record AppUserSessionState(Long userId, long authVersion) {
    }

    private String normalizeNickname(String nickname) {
        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = nickname.trim();
        int codePointCount = normalized.codePointCount(0, normalized.length());
        boolean hasForbiddenCharacter = normalized.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT);
        if (codePointCount < 2 || codePointCount > 32 || hasForbiddenCharacter) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String resolvedNickname(Long userId, String nickname) {
        return StringUtils.hasText(nickname) ? nickname : defaultNickname(userId);
    }

    private String defaultNickname(Long userId) {
        String id = Long.toString(userId);
        return "用户" + id.substring(Math.max(0, id.length() - 6));
    }
}
