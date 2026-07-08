package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.security.user.password=test-password")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void appHealthIsPublic() throws Exception {
        mockMvc.perform(get("/app/health"))
                .andExpect(status().isOk());
    }

    @Test
    void adminApisRequireAuthentication() throws Exception {
        mockMvc.perform(get("/admin/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void adminApisDoNotAcceptBasicAuthentication() throws Exception {
        mockMvc.perform(get("/admin/probe")
                        .with(httpBasic("user", "test-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void appApisExceptHealthRequireAuthentication() throws Exception {
        mockMvc.perform(get("/app/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void appProductReadApisArePublicButWriteApisStillRequireAuthentication() throws Exception {
        assertNotAuthenticationBlocked("/app/product/categories");
        assertNotAuthenticationBlocked("/app/product/spus");
        assertNotAuthenticationBlocked("/app/product/spus/1");

        mockMvc.perform(post("/app/product/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void validAdminTokenAuthenticatesAdminApi() throws Exception {
        String token = adminToken(List.of("R_SUPER"), List.of("product:read"));

        mockMvc.perform(get("/admin/probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind", is("ADMIN")))
                .andExpect(jsonPath("$.subjectId", is(1)));
    }

    @Test
    void validAppTokenAuthenticatesAppApi() throws Exception {
        String token = appToken();

        mockMvc.perform(get("/app/probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind", is("APP")))
                .andExpect(jsonPath("$.subjectId", is(2)));
    }

    @Test
    void wrongKindTokenIsUnauthorizedForOppositeNamespace() throws Exception {
        String adminToken = adminToken(List.of("R_SUPER"), List.of("product:read"));
        String appToken = appToken();

        mockMvc.perform(get("/app/probe")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
        mockMvc.perform(get("/admin/probe")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void invalidBearerTokenReturnsJsonUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(get("/admin/probe")
                        .header("Authorization", "Bearer adm_invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void publicEndpointsAreNotBlockedByAuthentication() throws Exception {
        assertNotAuthenticationBlocked("/admin/auth/login");
        assertNotAuthenticationBlocked("/app/auth/login");
        assertNotAuthenticationBlocked("/files/public/health-probe.png");
        assertNotAuthenticationBlocked("/wxpay/notify");
        assertNotAuthenticationBlocked("/wechat/events");
        assertNotAuthenticationBlocked("/actuator/health");
        assertNotAuthenticationBlocked("/actuator/info");
    }

    @Test
    void methodSecurityAccessDeniedReturnsJsonForbiddenEnvelope() throws Exception {
        String token = adminToken(List.of("R_MANAGER"), List.of("product:read"));

        mockMvc.perform(get("/admin/super-only")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(100003)))
                .andExpect(jsonPath("$.msg", is("Permission denied")));
    }

    @Test
    void roleCodeAlsoProvidesStandardRoleAuthority() throws Exception {
        String token = adminToken(List.of("R_SUPER"), List.of());

        mockMvc.perform(get("/admin/super-only")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void roleCodeAlsoProvidesRawAuthority() throws Exception {
        String token = adminToken(List.of("R_SUPER"), List.of());

        mockMvc.perform(get("/admin/raw-role-only")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void unknownNonApiPathsAreDeniedByFallbackPolicy() throws Exception {
        mockMvc.perform(get("/shop/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    private String adminToken(List<String> roles, List<String> permissions) {
        TokenSession session = TokenSession.admin(1L, "admin", roles, permissions, Instant.now());
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }

    private String appToken() {
        TokenSession session = TokenSession.app(2L, "openid***", Instant.now());
        return opaqueTokenService.issue(TokenKind.APP, session).accessToken();
    }

    private void assertNotAuthenticationBlocked(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeControllerConfiguration {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/admin/probe")
        AuthenticatedPrincipal adminProbe(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
            return principal;
        }

        @GetMapping("/app/probe")
        AuthenticatedPrincipal appProbe(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
            return principal;
        }

        @PostMapping("/app/product/categories")
        String appProductCategoryWriteProbe() {
            return "ok";
        }

        @GetMapping("/admin/super-only")
        @PreAuthorize("hasRole('SUPER')")
        String superOnly() {
            return "ok";
        }

        @GetMapping("/admin/raw-role-only")
        @PreAuthorize("hasAuthority('R_SUPER')")
        String rawRoleOnly() {
            return "ok";
        }

        @GetMapping("/shop/probe")
        String shopProbe() {
            return "ok";
        }
    }
}
