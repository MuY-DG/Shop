package org.muybaby.shopserver.auth.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.login.AdminLoginAttempt;
import org.muybaby.shopserver.auth.login.AdminLoginGuard;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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
        AdminLoginAttempt attempt = new AdminLoginAttempt("pair", "account", "ip");
        AdminAuthService adminAuthService = new AdminAuthService(
                adminRbacService, passwordEncoder, opaqueTokenService, adminLoginGuard);
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
}
