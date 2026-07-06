package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.admin.rbac.entity.AdminUser;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.dto.CurrentAdminUserResponse;
import org.muybaby.shopserver.auth.dto.LoginTokenResponse;
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

@Service
public class AdminAuthService {

    private final AdminRbacService adminRbacService;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenService opaqueTokenService;

    public AdminAuthService(
            AdminRbacService adminRbacService,
            PasswordEncoder passwordEncoder,
            OpaqueTokenService opaqueTokenService
    ) {
        this.adminRbacService = adminRbacService;
        this.passwordEncoder = passwordEncoder;
        this.opaqueTokenService = opaqueTokenService;
    }

    public LoginTokenResponse login(AdminLoginRequest request) {
        AdminUser user = adminRbacService.findEnabledUserByUsername(request.userName())
                .filter(adminUser -> passwordEncoder.matches(request.password(), adminUser.passwordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        List<String> roles = adminRbacService.roleCodesByUserId(user.id());
        List<String> permissions = adminRbacService.permissionMarksByUserId(user.id());
        TokenSession session = TokenSession.admin(user.id(), user.username(), roles, permissions, Instant.now());
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
