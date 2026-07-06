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
        LocalDateTime now = LocalDateTime.now();
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
                            INSERT INTO app_user (id, openid, unionid, status, last_login_at)
                            VALUES (:id, :openid, :unionid, :status, :lastLoginAt)
                            """)
                    .param("id", userId)
                    .param("openid", session.openid())
                    .param("unionid", session.unionid())
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
        int updatedRows = jdbcClient.sql("""
                        UPDATE app_user
                        SET phone_number = :phoneNumber,
                            phone_country_code = :phoneCountryCode,
                            phone_authorized = TRUE,
                            updated_at = :updatedAt
                        WHERE id = :id AND status = :status
                        """)
                .param("phoneNumber", phoneInfo.phoneNumber())
                .param("phoneCountryCode", phoneInfo.countryCode())
                .param("updatedAt", LocalDateTime.now())
                .param("id", userId)
                .param("status", ENABLED_STATUS)
                .update();

        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return findEnabledById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private Optional<AppUser> findByOpenid(String openid) {
        return jdbcClient.sql("""
                        SELECT id, openid, unionid, phone_number, phone_country_code, phone_authorized,
                               status, last_login_at, created_at, updated_at
                        FROM app_user
                        WHERE openid = :openid
                        """)
                .param("openid", openid)
                .query(this::mapRow)
                .optional();
    }

    private Optional<AppUser> findById(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, openid, unionid, phone_number, phone_country_code, phone_authorized,
                               status, last_login_at, created_at, updated_at
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

    private AppUser requireEnabled(AppUser user) {
        if (!isEnabled(user)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return user;
    }

    private boolean isEnabled(AppUser user) {
        return ENABLED_STATUS.equals(user.status());
    }

    private AppUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getLong("id"),
                rs.getString("openid"),
                rs.getString("unionid"),
                rs.getString("phone_number"),
                rs.getString("phone_country_code"),
                rs.getBoolean("phone_authorized"),
                rs.getString("status"),
                rs.getObject("last_login_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }
}
