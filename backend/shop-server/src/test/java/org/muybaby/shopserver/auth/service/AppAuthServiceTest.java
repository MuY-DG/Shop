package org.muybaby.shopserver.auth.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.dto.AppLoginRequest;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeRequest;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.user.service.AppUserService;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatMiniProgramClient;
import org.muybaby.shopserver.wechat.WechatPhoneInfo;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppAuthServiceTest {

    @Test
    void loginMasksShortOpenidWithoutLeakingFullValue() {
        WechatMiniProgramClient wechatClient = mock(WechatMiniProgramClient.class);
        AppUserService appUserService = mock(AppUserService.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AppAuthService appAuthService = new AppAuthService(wechatClient, appUserService, opaqueTokenService);

        when(wechatClient.code2Session("short-code")).thenReturn(new WechatCodeSession("abc", null, "session-key"));
        when(appUserService.upsertByOpenid(any())).thenReturn(appUser(1L, "abc", null, false));
        when(opaqueTokenService.issue(any(), any())).thenReturn(new TokenPair("app_token", "apr_token", 604800));

        assertThat(appAuthService.login(new AppLoginRequest("short-code")).user().openidMasked())
                .isEqualTo("a****c");
    }

    @Test
    void loginMasksOneAndTwoCharacterOpenidsWithoutRevealingAllCharacters() {
        WechatMiniProgramClient wechatClient = mock(WechatMiniProgramClient.class);
        AppUserService appUserService = mock(AppUserService.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AppAuthService appAuthService = new AppAuthService(wechatClient, appUserService, opaqueTokenService);

        when(wechatClient.code2Session("one-char-code")).thenReturn(new WechatCodeSession("a", null, "session-key"));
        when(wechatClient.code2Session("two-char-code")).thenReturn(new WechatCodeSession("ab", null, "session-key"));
        when(appUserService.upsertByOpenid(new WechatCodeSession("a", null, "session-key")))
                .thenReturn(appUser(1L, "a", null, false));
        when(appUserService.upsertByOpenid(new WechatCodeSession("ab", null, "session-key")))
                .thenReturn(appUser(2L, "ab", null, false));
        when(opaqueTokenService.issue(any(), any())).thenReturn(new TokenPair("app_token", "apr_token", 604800));

        assertThat(appAuthService.login(new AppLoginRequest("one-char-code")).user().openidMasked())
                .isEqualTo("****");
        assertThat(appAuthService.login(new AppLoginRequest("two-char-code")).user().openidMasked())
                .isEqualTo("****");
    }

    @Test
    void authorizePhoneMasksShortPhoneWithoutLeakingFullValue() {
        WechatMiniProgramClient wechatClient = mock(WechatMiniProgramClient.class);
        AppUserService appUserService = mock(AppUserService.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AppAuthService appAuthService = new AppAuthService(wechatClient, appUserService, opaqueTokenService);

        when(wechatClient.getPhoneNumber("phone-code")).thenReturn(new WechatPhoneInfo("12345", "12345", "86"));
        when(appUserService.markPhoneAuthorized(1L, new WechatPhoneInfo("12345", "12345", "86")))
                .thenReturn(appUser(1L, "openid", "12345", true));

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(TokenKind.APP, 1L, "openid", List.of(), List.of());

        assertThat(appAuthService.authorizePhone(principal, new PhoneAuthorizeRequest("phone-code")).phoneNumberMasked())
                .isEqualTo("1****5");
    }

    @Test
    void authorizePhoneMasksOneAndTwoCharacterPhonesWithoutRevealingAllCharacters() {
        WechatMiniProgramClient wechatClient = mock(WechatMiniProgramClient.class);
        AppUserService appUserService = mock(AppUserService.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AppAuthService appAuthService = new AppAuthService(wechatClient, appUserService, opaqueTokenService);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(TokenKind.APP, 1L, "openid", List.of(), List.of());

        when(wechatClient.getPhoneNumber("one-char-phone-code")).thenReturn(new WechatPhoneInfo("1", "1", "86"));
        when(wechatClient.getPhoneNumber("two-char-phone-code")).thenReturn(new WechatPhoneInfo("12", "12", "86"));
        when(appUserService.markPhoneAuthorized(1L, new WechatPhoneInfo("1", "1", "86")))
                .thenReturn(appUser(1L, "openid", "1", true));
        when(appUserService.markPhoneAuthorized(1L, new WechatPhoneInfo("12", "12", "86")))
                .thenReturn(appUser(1L, "openid", "12", true));

        assertThat(appAuthService.authorizePhone(principal, new PhoneAuthorizeRequest("one-char-phone-code")).phoneNumberMasked())
                .isEqualTo("****");
        assertThat(appAuthService.authorizePhone(principal, new PhoneAuthorizeRequest("two-char-phone-code")).phoneNumberMasked())
                .isEqualTo("****");
    }

    private AppUser appUser(Long id, String openid, String phoneNumber, Boolean phoneAuthorized) {
        LocalDateTime now = LocalDateTime.now();
        return new AppUser(id, openid, null, phoneNumber, "86", phoneAuthorized, "ENABLED", now, now, now);
    }
}
