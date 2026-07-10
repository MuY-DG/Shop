package org.muybaby.shopserver.auth.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.dto.AppLoginRequest;
import org.muybaby.shopserver.auth.dto.AppSessionResponse;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeRequest;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.user.service.AppUserService;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatMiniProgramClient;
import org.muybaby.shopserver.wechat.WechatPhoneInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppAuthServiceTest {

    @Test
    void loginMasksShortOpenidWithoutLeakingFullValue() {
        Fixture fixture = fixture();
        when(fixture.wechatClient().code2Session("short-code"))
                .thenReturn(new WechatCodeSession("abc", null, "session-key"));
        when(fixture.appUserService().upsertByOpenid(any())).thenReturn(appUser(1L, "abc", null, false));
        when(fixture.opaqueTokenService().issue(any(), any()))
                .thenReturn(new TokenPair("app_token", "apr_token", 604800));

        assertThat(fixture.service().login(new AppLoginRequest("short-code")).user().openidMasked())
                .isEqualTo("a****c");
    }

    @Test
    void canonicalMapperMasksOneAndTwoCharacterValuesWithoutRevealingThem() {
        AppUserProfileMapper mapper = new AppUserProfileMapper();

        assertThat(mapper.from(appUser(1L, "a", "1", true)).openidMasked()).isEqualTo("****");
        assertThat(mapper.from(appUser(1L, "a", "1", true)).phoneNumberMasked()).isEqualTo("****");
        assertThat(mapper.from(appUser(2L, "ab", "12", true)).openidMasked()).isEqualTo("****");
        assertThat(mapper.from(appUser(2L, "ab", "12", true)).phoneNumberMasked()).isEqualTo("****");
    }

    @Test
    void authorizePhoneUsesCanonicalProfileMapper() {
        Fixture fixture = fixture();
        WechatPhoneInfo phoneInfo = new WechatPhoneInfo("12345", "12345", "86");
        when(fixture.wechatClient().getPhoneNumber("phone-code")).thenReturn(phoneInfo);
        when(fixture.appUserService().markPhoneAuthorized(1L, phoneInfo))
                .thenReturn(appUser(1L, "openid", "12345", true));

        AuthenticatedPrincipal principal = appPrincipal("session-1", 1L);

        assertThat(fixture.service().authorizePhone(principal, new PhoneAuthorizeRequest("phone-code")))
                .satisfies(profile -> {
                    assertThat(profile.userId()).isEqualTo(1L);
                    assertThat(profile.openidMasked()).isEqualTo("o****d");
                    assertThat(profile.phoneAuthorized()).isTrue();
                    assertThat(profile.phoneNumberMasked()).isEqualTo("1****5");
                });
    }

    @Test
    void refreshConsumesOldTokenReReadsUserAndIssuesFreshSession() {
        Fixture fixture = fixture();
        TokenSession oldSession = new TokenSession(
                "old-session",
                TokenKind.APP,
                7L,
                "old-mask",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        AppUser currentUser = appUser(7L, "current-openid", "13812345678", true);
        when(fixture.opaqueTokenService().consumeRefreshToken("apr_old", TokenKind.APP)).thenReturn(oldSession);
        when(fixture.appUserService().requireEnabledUser(7L)).thenReturn(currentUser);
        when(fixture.opaqueTokenService().issue(any(), any()))
                .thenReturn(new TokenPair("app_new", "apr_new", 604800));

        AppSessionResponse response = fixture.service().refresh(new RefreshTokenRequest("apr_old"));

        assertThat(response.token()).isEqualTo("app_new");
        assertThat(response.refreshToken()).isEqualTo("apr_new");
        assertThat(response.user().userId()).isEqualTo(7L);
        assertThat(response.user().openidMasked()).isEqualTo("curr****enid");
        assertThat(response.user().phoneNumberMasked()).isEqualTo("138****5678");
        verify(fixture.opaqueTokenService()).consumeRefreshToken("apr_old", TokenKind.APP);
        verify(fixture.appUserService()).requireEnabledUser(7L);
    }

    @Test
    void meReReadsEnabledUserAndUsesCanonicalProfile() {
        Fixture fixture = fixture();
        when(fixture.appUserService().requireEnabledUser(3L))
                .thenReturn(appUser(3L, "profile-openid", "13812345678", true));

        assertThat(fixture.service().me(appPrincipal("session-3", 3L)))
                .satisfies(profile -> {
                    assertThat(profile.userId()).isEqualTo(3L);
                    assertThat(profile.openidMasked()).isEqualTo("prof****enid");
                    assertThat(profile.phoneAuthorized()).isTrue();
                    assertThat(profile.phoneNumberMasked()).isEqualTo("138****5678");
                });
    }

    @Test
    void logoutRevokesTheAuthenticatedAppSession() {
        Fixture fixture = fixture();

        fixture.service().logout(appPrincipal("session-to-revoke", 5L));

        verify(fixture.opaqueTokenService()).revokeSession("session-to-revoke", TokenKind.APP);
    }

    @Test
    void authorizePhoneRejectsDisabledUserBeforeCallingWechat() {
        Fixture fixture = fixture();
        when(fixture.appUserService().requireEnabledUser(1L))
                .thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));

        assertThatThrownBy(() -> fixture.service().authorizePhone(
                appPrincipal("session-1", 1L),
                new PhoneAuthorizeRequest("phone-code")
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(fixture.wechatClient());
    }

    @Test
    void meAndLogoutRejectNonAppPrincipals() {
        Fixture fixture = fixture();
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(
                "admin-session",
                TokenKind.ADMIN,
                1L,
                "admin",
                List.of(),
                List.of()
        );

        assertAuthenticationRequired(() -> fixture.service().me(admin));
        assertAuthenticationRequired(() -> fixture.service().logout(admin));
    }

    private void assertAuthenticationRequired(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private Fixture fixture() {
        WechatMiniProgramClient wechatClient = mock(WechatMiniProgramClient.class);
        AppUserService appUserService = mock(AppUserService.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AppAuthService service = new AppAuthService(
                wechatClient,
                appUserService,
                opaqueTokenService,
                new AppUserProfileMapper()
        );
        return new Fixture(wechatClient, appUserService, opaqueTokenService, service);
    }

    private AuthenticatedPrincipal appPrincipal(String sessionId, long userId) {
        return new AuthenticatedPrincipal(sessionId, TokenKind.APP, userId, "openid", List.of(), List.of());
    }

    private AppUser appUser(Long id, String openid, String phoneNumber, Boolean phoneAuthorized) {
        LocalDateTime now = LocalDateTime.now();
        return new AppUser(id, openid, null, phoneNumber, "86", phoneAuthorized, "ENABLED", now, now, now);
    }

    private record Fixture(
            WechatMiniProgramClient wechatClient,
            AppUserService appUserService,
            OpaqueTokenService opaqueTokenService,
            AppAuthService service
    ) {
    }
}
