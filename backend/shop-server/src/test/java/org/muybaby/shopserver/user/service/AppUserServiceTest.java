package org.muybaby.shopserver.user.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatPhoneInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppUserServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void duplicateOpenidInsertedBetweenReadAndInsertReturnsExistingUser() {
        AppUserService appUserService = new AppUserService(jdbcClientThatCreatesDuplicateBeforeInsert());

        AppUser user = appUserService.upsertByOpenid(new WechatCodeSession("race-openid", "new-unionid", "session-key"));

        assertThat(user.id()).isEqualTo(9001L);
        assertThat(user.openid()).isEqualTo("race-openid");
        assertThat(user.unionid()).isEqualTo("existing-unionid");
    }

    @Test
    void disabledAppUserCannotLoginAgain() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9101L, "disabled-openid", "DISABLED");

        assertThatThrownBy(() -> appUserService.upsertByOpenid(
                new WechatCodeSession("disabled-openid", "unionid", "session-key")
        ))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    void disabledAppUserCannotAuthorizePhone() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9102L, "disabled-phone-openid", "DISABLED");

        assertThatThrownBy(() -> appUserService.markPhoneAuthorized(
                9102L,
                new WechatPhoneInfo("13812345678", "13812345678", "86")
        ))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));

        Boolean phoneAuthorized = jdbcClient.sql("select phone_authorized from app_user where id = :id")
                .param("id", 9102L)
                .query(Boolean.class)
                .single();
        assertThat(phoneAuthorized).isFalse();
    }

    private JdbcClient jdbcClientThatCreatesDuplicateBeforeInsert() {
        AtomicBoolean duplicateCreated = new AtomicBoolean(false);
        return (JdbcClient) Proxy.newProxyInstance(
                JdbcClient.class.getClassLoader(),
                new Class<?>[]{JdbcClient.class},
                (proxy, method, args) -> {
                    if ("sql".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] instanceof String sql
                            && sql.contains("INSERT INTO app_user")
                            && duplicateCreated.compareAndSet(false, true)) {
                        insertExistingRaceUser();
                    }
                    return method.invoke(jdbcClient, args);
                }
        );
    }

    private void insertExistingRaceUser() {
        insertAppUser(9001L, "race-openid", "ENABLED");
        jdbcClient.sql("update app_user set unionid = :unionid where id = :id")
                .param("unionid", "existing-unionid")
                .param("id", 9001L)
                .update();
    }

    private void insertAppUser(Long id, String openid, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        INSERT INTO app_user (id, openid, unionid, status, last_login_at)
                        VALUES (:id, :openid, :unionid, :status, :lastLoginAt)
                        """)
                .param("id", id)
                .param("openid", openid)
                .param("unionid", "existing-unionid")
                .param("status", status)
                .param("lastLoginAt", now)
                .update();
    }
}
