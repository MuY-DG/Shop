package org.muybaby.shopserver.auth.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.muybaby.shopserver.auth.dto.AppLoginRequest;
import org.muybaby.shopserver.auth.dto.AppSessionResponse;
import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeRequest;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.dto.UpdateAppUserProfileRequest;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.InMemoryTokenStore;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.auth.token.TokenProperties;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.user.service.AppUserService;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatMiniProgramClient;
import org.muybaby.shopserver.wechat.WechatPhoneInfo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

        AppUserProfile profile = fixture.service().login(new AppLoginRequest("short-code")).user();

        assertThat(profile.openidMasked()).isEqualTo("a****c");
        assertThat(profile.nickname()).isEqualTo("测试用户");
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
    void refreshKeepsTheSessionFamilyAndRotatesTheGeneration() {
        Fixture fixture = fixture();
        TokenSession oldSession = new TokenSession(
                "family-7",
                "11111111-1111-4111-8111-111111111111",
                TokenKind.APP,
                7L,
                "old-mask",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        when(fixture.opaqueTokenService().consumeRefreshToken("apr_old", TokenKind.APP)).thenReturn(oldSession);
        when(fixture.appUserService().requireEnabledUser(7L))
                .thenReturn(appUser(7L, "current-openid", null, false));
        when(fixture.opaqueTokenService().issue(any(), any()))
                .thenReturn(new TokenPair("app_new", "apr_new", 604800));

        fixture.service().refresh(new RefreshTokenRequest("apr_old"));

        ArgumentCaptor<TokenSession> sessionCaptor = ArgumentCaptor.forClass(TokenSession.class);
        verify(fixture.opaqueTokenService()).issue(org.mockito.ArgumentMatchers.eq(TokenKind.APP), sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().sessionId()).isEqualTo("family-7");
        assertThat(sessionCaptor.getValue().generationId())
                .isNotBlank()
                .isNotEqualTo("11111111-1111-4111-8111-111111111111")
                .isNotEqualTo("family-7");
    }

    @Test
    void logoutAfterRefreshConsumptionPreventsTokenResurrection() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore tokenStore = new InMemoryTokenStore(clock);
        OpaqueTokenService tokenService = new OpaqueTokenService(
                tokenStore,
                new TokenProperties(
                        Duration.ofHours(2),
                        Duration.ofDays(7),
                        Duration.ofHours(1),
                        Duration.ofDays(1)
                )
        );
        AppUserService appUserService = mock(AppUserService.class);
        AppAuthService service = new AppAuthService(
                mock(WechatMiniProgramClient.class),
                appUserService,
                tokenService,
                new AppUserProfileMapper()
        );
        TokenSession loginSession = TokenSession.app(7L, "openid", clock.instant());
        TokenPair loginPair = tokenService.issue(TokenKind.APP, loginSession);
        CountDownLatch refreshConsumed = new CountDownLatch(1);
        CountDownLatch allowRefreshToIssue = new CountDownLatch(1);
        when(appUserService.requireEnabledUser(7L)).thenAnswer(invocation -> {
            refreshConsumed.countDown();
            if (!allowRefreshToIssue.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to resume refresh");
            }
            return appUser(7L, "current-openid", null, false);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<AppSessionResponse> refresh = executor.submit(
                    () -> service.refresh(new RefreshTokenRequest(loginPair.refreshToken()))
            );
            assertThat(refreshConsumed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tokenStore.isGenerationRevoked(loginSession.generationId())).isTrue();

            service.logout(appPrincipal(loginSession.sessionId(), 7L));
            assertThat(tokenStore.isSessionRevoked(loginSession.sessionId())).isTrue();
            allowRefreshToIssue.countDown();

            assertThatThrownBy(() -> refresh.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class, cause ->
                                    assertThat(cause.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED)));
            assertThat(tokenService.lookupAccessToken(loginPair.accessToken(), TokenKind.APP)).isEmpty();
        } finally {
            allowRefreshToIssue.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
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
    void updateProfileUsesTheAuthenticatedUserAndCanonicalMapper() {
        Fixture fixture = fixture();
        when(fixture.appUserService().updateNickname(3L, "山茶花用户"))
                .thenReturn(appUser(3L, "profile-openid", null, false, "山茶花用户"));

        assertThat(fixture.service().updateProfile(
                appPrincipal("session-3", 3L),
                new UpdateAppUserProfileRequest("山茶花用户")
        )).satisfies(profile -> {
            assertThat(profile.userId()).isEqualTo(3L);
            assertThat(profile.nickname()).isEqualTo("山茶花用户");
        });
        verify(fixture.appUserService()).updateNickname(3L, "山茶花用户");
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
        return appUser(id, openid, phoneNumber, phoneAuthorized, "测试用户");
    }

    private AppUser appUser(
            Long id,
            String openid,
            String phoneNumber,
            Boolean phoneAuthorized,
            String nickname
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new AppUser(
                id, openid, null, nickname, phoneNumber, "86", phoneAuthorized,
                "ENABLED", now, now, now
        );
    }

    private record Fixture(
            WechatMiniProgramClient wechatClient,
            AppUserService appUserService,
            OpaqueTokenService opaqueTokenService,
            AppAuthService service
    ) {
    }
}
