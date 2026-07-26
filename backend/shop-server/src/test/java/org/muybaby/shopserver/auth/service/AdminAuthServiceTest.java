package org.muybaby.shopserver.auth.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.rbac.entity.AdminUser;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.login.AdminLoginAttempt;
import org.muybaby.shopserver.auth.login.AdminLoginGuard;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthServiceTest {

    @Test
    void loginRunsPasswordMatchWhenUserLookupMisses() {
        AdminRbacService adminRbacService = mock(AdminRbacService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AdminLoginGuard adminLoginGuard = mock(AdminLoginGuard.class);
        AdminLastLoginService adminLastLoginService = mock(AdminLastLoginService.class);
        AdminLoginAttempt attempt = new AdminLoginAttempt("pair", "account", "ip");
        AdminAuthService adminAuthService = new AdminAuthService(
                adminRbacService,
                passwordEncoder,
                opaqueTokenService,
                adminLoginGuard,
                adminLastLoginService
        );
        when(adminLoginGuard.start("Missing", "198.51.100.8")).thenReturn(attempt);
        when(adminRbacService.findEnabledUserByUsername("Missing")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> adminAuthService.login(
                new AdminLoginRequest("Missing", "123456"), "198.51.100.8"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(passwordEncoder).matches(eq("123456"), anyString());
        verify(adminLoginGuard).recordFailure(attempt);
    }

    @Test
    void loginReturnsIssuedTokensWhenIndependentLastLoginWriteFails() {
        AdminRbacService adminRbacService = mock(AdminRbacService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AdminLoginGuard adminLoginGuard = mock(AdminLoginGuard.class);
        AdminLastLoginWriter writer = mock(AdminLastLoginWriter.class);
        ZoneId businessZone = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T03:04:05Z"), businessZone);
        LocalDateTime expectedLastLoginAt = LocalDateTime.now(clock);
        AdminLastLoginService adminLastLoginService = new AdminLastLoginService(writer, clock);
        AdminAuthService adminAuthService = new AdminAuthService(
                adminRbacService,
                passwordEncoder,
                opaqueTokenService,
                adminLoginGuard,
                adminLastLoginService
        );
        AdminUser user = new AdminUser(
                1L,
                "Super",
                "password-hash",
                "Super",
                "super@shop.local",
                "",
                "ENABLED",
                null,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
        AdminLoginAttempt suppliedAttempt = new AdminLoginAttempt("pair-name", "account-name", "ip");
        AdminLoginAttempt userAttempt = new AdminLoginAttempt("pair-id", "account-id", "ip");
        when(adminLoginGuard.start("Super", "198.51.100.9")).thenReturn(suppliedAttempt);
        when(adminLoginGuard.start(1L, "198.51.100.9")).thenReturn(userAttempt);
        when(adminRbacService.findEnabledUserByUsername("Super")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("123456", "password-hash")).thenReturn(true);
        when(adminRbacService.roleCodesByUserId(1L)).thenReturn(List.of("R_SUPER"));
        when(adminRbacService.permissionMarksByUserId(1L)).thenReturn(List.of("system:log:read"));
        when(opaqueTokenService.issue(eq(TokenKind.ADMIN), any()))
                .thenReturn(new TokenPair("adm_token", "adr_token", 7200L));
        doThrow(new IllegalStateException("last-login-database-secret"))
                .when(writer)
                .update(1L, expectedLastLoginAt);

        AdminLoginResult result = adminAuthService.login(
                new AdminLoginRequest("Super", "123456"),
                "198.51.100.9"
        );

        assertThat(result.tokens().token()).isEqualTo("adm_token");
        assertThat(result.tokens().refreshToken()).isEqualTo("adr_token");
        var inOrder = inOrder(opaqueTokenService, writer);
        inOrder.verify(opaqueTokenService).issue(eq(TokenKind.ADMIN), any());
        inOrder.verify(writer).update(1L, expectedLastLoginAt);
    }
}
