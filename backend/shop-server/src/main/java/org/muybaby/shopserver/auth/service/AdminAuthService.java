package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.admin.rbac.entity.AdminUser;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.dto.CurrentAdminUserResponse;
import org.muybaby.shopserver.auth.dto.LoginTokenResponse;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.login.AdminLoginAttempt;
import org.muybaby.shopserver.auth.login.AdminLoginGuard;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AdminAuthService {

    private static final String DUMMY_PASSWORD_HASH = "$2a$10$dSCU.t56l8Z7MPya89bXnuiMIjScayWL.KeTgc92TqlfLu.woUoYm";

    private final AdminRbacService adminRbacService;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenService opaqueTokenService;
    private final AdminLoginGuard adminLoginGuard;
    private final AdminLastLoginService adminLastLoginService;

    public AdminAuthService(
            AdminRbacService adminRbacService,
            PasswordEncoder passwordEncoder,
            OpaqueTokenService opaqueTokenService,
            AdminLoginGuard adminLoginGuard,
            AdminLastLoginService adminLastLoginService
    ) {
        this.adminRbacService = adminRbacService;
        this.passwordEncoder = passwordEncoder;
        this.opaqueTokenService = opaqueTokenService;
        this.adminLoginGuard = adminLoginGuard;
        this.adminLastLoginService = adminLastLoginService;
    }

    public AdminLoginResult login(AdminLoginRequest request, String clientIp) {
        String username = request.userName().strip();
        AdminLoginAttempt suppliedIdentityAttempt = adminLoginGuard.start(username, clientIp);
        Optional<AdminUser> userResult = adminRbacService.findEnabledUserByUsername(username);
        AdminLoginAttempt attempt = userResult
                .map(user -> adminLoginGuard.start(user.id(), clientIp))
                .orElse(suppliedIdentityAttempt);
        String passwordHash = userResult.map(AdminUser::passwordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        if (userResult.isEmpty() || !passwordMatches) {
            adminLoginGuard.recordFailure(attempt);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        adminLoginGuard.recordSuccess(attempt);
        AdminUser user = userResult.get();
        LoginTokenResponse tokens = issueSession(user, null);
        adminLastLoginService.updateBestEffort(user.id());
        return new AdminLoginResult(tokens, user.id(), user.username());
    }

    public LoginTokenResponse refresh(RefreshTokenRequest request) {
        TokenSession oldSession = opaqueTokenService.consumeRefreshToken(request.refreshToken(), TokenKind.ADMIN);
        AdminUser user = adminRbacService.findEnabledUserById(oldSession.subjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        return issueSession(user, oldSession.sessionId());
    }

    private LoginTokenResponse issueSession(AdminUser user, String sessionId) {
        List<String> roles = adminRbacService.roleCodesByUserId(user.id());
        List<String> permissions = adminRbacService.permissionMarksByUserId(user.id());
        TokenSession session = sessionId == null
                ? TokenSession.admin(user.id(), user.username(), roles, permissions, Instant.now())
                : TokenSession.admin(sessionId, user.id(), user.username(), roles, permissions, Instant.now());
        TokenPair tokenPair = opaqueTokenService.issue(TokenKind.ADMIN, session);

        return new LoginTokenResponse(tokenPair.accessToken(), tokenPair.refreshToken(), tokenPair.expiresIn());
    }

    public CurrentAdminUserResponse currentUser(AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        AdminUser user = adminRbacService.findEnabledUserById(principal.subjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));

        return new CurrentAdminUserResponse(
                user.id(),
                user.username(),
                user.email(),
                user.avatar(),
                principal.roles(),
                principal.permissions()
        );
    }
}
