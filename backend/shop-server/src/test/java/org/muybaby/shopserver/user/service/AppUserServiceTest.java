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
    void newUserReceivesAStableDefaultNickname() {
        AppUserService appUserService = new AppUserService(jdbcClient);

        AppUser user = appUserService.upsertByOpenid(
                new WechatCodeSession("nickname-new-openid", null, "session-key")
        );

        assertThat(user.nickname()).startsWith("用户");
        assertThat(jdbcClient.sql("select nickname from app_user where id = :id")
                .param("id", user.id())
                .query(String.class)
                .single()).isEqualTo(user.nickname());
    }

    @Test
    void enabledUserCanUpdateNicknameWithTrimmedValue() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9105L, "nickname-update-openid", "ENABLED");

        AppUser updated = appUserService.updateNickname(9105L, "  山茶花用户  ");

        assertThat(updated.nickname()).isEqualTo("山茶花用户");
        assertThat(jdbcClient.sql("select nickname from app_user where id = 9105")
                .query(String.class)
                .single()).isEqualTo("山茶花用户");
    }

    @Test
    void nicknameRejectsInvalidLengthControlCharactersAndDisabledUsers() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9106L, "nickname-validation-openid", "ENABLED");
        insertAppUser(9107L, "nickname-disabled-openid", "DISABLED");

        assertValidationFailed(() -> appUserService.updateNickname(9106L, "单"));
        assertValidationFailed(() -> appUserService.updateNickname(9106L, "用户\u0001名称"));
        assertValidationFailed(() -> appUserService.updateNickname(9106L, "用".repeat(33)));
        assertThatThrownBy(() -> appUserService.updateNickname(9107L, "不可修改"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
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

    @Test
    void phoneAuthorizationCapturesTheFirstAuthorizationTime() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9108L, "phone-time-openid", "ENABLED");

        AppUser authorized = appUserService.markPhoneAuthorized(
                9108L,
                new WechatPhoneInfo("13812345678", "13812345678", "86")
        );
        LocalDateTime firstAuthorizedAt = jdbcClient.sql("""
                        select phone_authorized_at
                        from app_user
                        where id = 9108
                        """)
                .query(LocalDateTime.class)
                .single();

        assertThat(authorized.phoneAuthorized()).isTrue();
        assertThat(firstAuthorizedAt).isNotNull();

        appUserService.markPhoneAuthorized(
                9108L,
                new WechatPhoneInfo("13912345678", "13912345678", "86")
        );
        assertThat(jdbcClient.sql("select phone_authorized_at from app_user where id = 9108")
                .query(LocalDateTime.class)
                .single()).isEqualTo(firstAuthorizedAt);
    }

    @Test
    void requireEnabledUserReturnsEnabledUser() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9103L, "enabled-require-openid", "ENABLED");

        AppUser user = appUserService.requireEnabledUser(9103L);

        assertThat(user.id()).isEqualTo(9103L);
        assertThat(user.openid()).isEqualTo("enabled-require-openid");
    }

    @Test
    void requireEnabledUserRejectsDisabledUser() {
        AppUserService appUserService = new AppUserService(jdbcClient);
        insertAppUser(9104L, "disabled-require-openid", "DISABLED");

        assertThatThrownBy(() -> appUserService.requireEnabledUser(9104L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
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

    private void assertValidationFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
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
