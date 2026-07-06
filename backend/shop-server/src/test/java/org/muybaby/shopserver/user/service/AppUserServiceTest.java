package org.muybaby.shopserver.user.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

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
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        INSERT INTO app_user (id, openid, unionid, status, last_login_at)
                        VALUES (:id, :openid, :unionid, :status, :lastLoginAt)
                        """)
                .param("id", 9001L)
                .param("openid", "race-openid")
                .param("unionid", "existing-unionid")
                .param("status", "ENABLED")
                .param("lastLoginAt", now)
                .update();
    }
}
