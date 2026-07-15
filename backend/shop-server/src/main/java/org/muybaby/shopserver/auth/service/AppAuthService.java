package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.auth.dto.AppLoginRequest;
import org.muybaby.shopserver.auth.dto.AppSessionResponse;
import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeRequest;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.dto.UpdateAppUserProfileRequest;
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
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AppAuthService {

    private final WechatMiniProgramClient wechatClient;
    private final AppUserService appUserService;
    private final OpaqueTokenService opaqueTokenService;
    private final AppUserProfileMapper profileMapper;

    public AppAuthService(
            WechatMiniProgramClient wechatClient,
            AppUserService appUserService,
            OpaqueTokenService opaqueTokenService,
            AppUserProfileMapper profileMapper
    ) {
        this.wechatClient = wechatClient;
        this.appUserService = appUserService;
        this.opaqueTokenService = opaqueTokenService;
        this.profileMapper = profileMapper;
    }

    public AppSessionResponse login(AppLoginRequest request) {
        WechatCodeSession codeSession = wechatClient.code2Session(request.code());
        AppUser user = appUserService.upsertByOpenid(codeSession);
        return issueSession(user, null);
    }

    public AppSessionResponse refresh(RefreshTokenRequest request) {
        TokenSession oldSession = opaqueTokenService.consumeRefreshToken(request.refreshToken(), TokenKind.APP);
        AppUser user = appUserService.requireEnabledUser(oldSession.subjectId());
        return issueSession(user, oldSession.sessionId());
    }

    public AppUserProfile authorizePhone(AuthenticatedPrincipal principal, PhoneAuthorizeRequest request) {
        requireAppPrincipal(principal);
        appUserService.requireEnabledUser(principal.subjectId());
        WechatPhoneInfo phoneInfo = wechatClient.getPhoneNumber(request.code());
        AppUser user = appUserService.markPhoneAuthorized(principal.subjectId(), phoneInfo);
        return profileMapper.from(user);
    }

    public AppUserProfile me(AuthenticatedPrincipal principal) {
        requireAppPrincipal(principal);
        return profileMapper.from(appUserService.requireEnabledUser(principal.subjectId()));
    }

    public AppUserProfile updateProfile(
            AuthenticatedPrincipal principal,
            UpdateAppUserProfileRequest request
    ) {
        requireAppPrincipal(principal);
        return profileMapper.from(appUserService.updateNickname(principal.subjectId(), request.nickname()));
    }

    public void logout(AuthenticatedPrincipal principal) {
        requireAppPrincipal(principal);
        opaqueTokenService.revokeSession(principal.sessionId(), TokenKind.APP);
    }

    private AppSessionResponse issueSession(AppUser user, String sessionId) {
        AppUserProfile profile = profileMapper.from(user);
        TokenSession tokenSession = sessionId == null
                ? TokenSession.app(user.id(), profile.openidMasked(), Instant.now())
                : TokenSession.app(sessionId, user.id(), profile.openidMasked(), Instant.now());
        TokenPair tokenPair = opaqueTokenService.issue(TokenKind.APP, tokenSession);
        return new AppSessionResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                tokenPair.expiresIn(),
                profile
        );
    }

    private void requireAppPrincipal(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
