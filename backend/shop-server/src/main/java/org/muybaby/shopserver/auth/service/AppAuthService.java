package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.auth.dto.AppLoginRequest;
import org.muybaby.shopserver.auth.dto.AppLoginResponse;
import org.muybaby.shopserver.auth.dto.AppUserSummary;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeRequest;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeResponse;
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

    public AppAuthService(
            WechatMiniProgramClient wechatClient,
            AppUserService appUserService,
            OpaqueTokenService opaqueTokenService
    ) {
        this.wechatClient = wechatClient;
        this.appUserService = appUserService;
        this.opaqueTokenService = opaqueTokenService;
    }

    public AppLoginResponse login(AppLoginRequest request) {
        WechatCodeSession session = wechatClient.code2Session(request.code());
        AppUser user = appUserService.upsertByOpenid(session);
        String openidMasked = maskOpenid(user.openid());
        TokenSession tokenSession = TokenSession.app(user.id(), openidMasked, Instant.now());
        TokenPair tokenPair = opaqueTokenService.issue(TokenKind.APP, tokenSession);

        return new AppLoginResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                tokenPair.expiresIn(),
                new AppUserSummary(user.id(), openidMasked, Boolean.TRUE.equals(user.phoneAuthorized()))
        );
    }

    public PhoneAuthorizeResponse authorizePhone(AuthenticatedPrincipal principal, PhoneAuthorizeRequest request) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        appUserService.requireEnabledUser(principal.subjectId());
        WechatPhoneInfo phoneInfo = wechatClient.getPhoneNumber(request.code());
        AppUser user = appUserService.markPhoneAuthorized(principal.subjectId(), phoneInfo);
        return new PhoneAuthorizeResponse(Boolean.TRUE.equals(user.phoneAuthorized()), maskPhone(user.phoneNumber()));
    }

    private String maskOpenid(String openid) {
        return maskValue(openid, 4, 4);
    }

    private String maskPhone(String phoneNumber) {
        return maskValue(phoneNumber, 3, 4);
    }

    private String maskValue(String value, int preferredPrefixLength, int preferredSuffixLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 2) {
            return "****";
        }
        if (value.length() <= preferredPrefixLength + preferredSuffixLength) {
            return value.substring(0, 1) + "****" + value.substring(value.length() - 1);
        }
        return value.substring(0, preferredPrefixLength) + "****" + value.substring(value.length() - preferredSuffixLength);
    }
}
