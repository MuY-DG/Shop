package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ApplicationContext applicationContext;

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
    void adminSystemLogFilterIsSecurityChainOnlyAndSkipsAnonymousProbes() throws Exception {
        FilterRegistrationBean<?> registration = applicationContext.getBean(
                "adminSystemLogFilterRegistration",
                FilterRegistrationBean.class
        );
        assertThat(registration.isEnabled()).isFalse();

        String anonymousRequestId = "security-log-anonymous";
        mockMvc.perform(get("/admin/probe")
                        .header("X-Request-Id", anonymousRequestId))
                .andExpect(status().isUnauthorized());
        assertThat(logCount(anonymousRequestId)).isZero();

        String authenticatedRequestId = "security-log-authenticated";
        String token = adminToken(List.of("R_SUPER"), List.of("product:read"));
        mockMvc.perform(get("/admin/probe")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", authenticatedRequestId))
                .andExpect(status().isOk());
        assertThat(logCount(authenticatedRequestId)).isZero();

        String failedWriteRequestId = "security-log-failed-write";
        mockMvc.perform(post("/admin/probe")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", failedWriteRequestId))
                .andExpect(status().isMethodNotAllowed());
        assertThat(logCount(failedWriteRequestId)).isEqualTo(1);
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
    void unsupportedMethodReturnsMethodNotAllowedEnvelopeAndAllowHeader() throws Exception {
        String token = adminToken(List.of("R_SUPER"), List.of("product:read"));

        mockMvc.perform(post("/admin/probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code", is(100400)))
                .andExpect(jsonPath("$.msg", is("Validation failed")))
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void appApisExceptHealthRequireAuthentication() throws Exception {
        mockMvc.perform(get("/app/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void exactlyTheAppRefreshEndpointIsPublicWhileLogoutMeAndRefreshSubpathsStayProtected() throws Exception {
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)));

        mockMvc.perform(post("/app/auth/refresh/extra"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/app/auth/logout"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/app/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appProductReadApisArePublicButWriteApisStillRequireAuthentication() throws Exception {
        assertNotAuthenticationBlocked("/app/product/categories");
        assertNotAuthenticationBlocked("/app/product/filter-facets");
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
    void validAdminTokenGetsNotFoundEnvelopeForUnknownAdminApi() throws Exception {
        String token = adminToken(List.of("R_SUPER"), List.of("product:read"));

        mockMvc.perform(get("/admin/missing-api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(100404)))
                .andExpect(jsonPath("$.msg", is("Resource not found")));
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
    void appAccessTokenRequiresLiveEnabledStatusAndMatchingAuthVersion() throws Exception {
        String oldToken = appToken();

        jdbcClient.sql("update app_user set auth_version = auth_version + 1 where id = 2")
                .update();
        mockMvc.perform(get("/app/probe")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        long currentVersion = jdbcClient.sql("select auth_version from app_user where id = 2")
                .query(Long.class)
                .single();
        String currentToken = opaqueTokenService.issue(
                TokenKind.APP,
                TokenSession.app(2L, "openid***", currentVersion, Instant.now())
        ).accessToken();
        mockMvc.perform(get("/app/probe")
                        .header("Authorization", "Bearer " + currentToken))
                .andExpect(status().isOk());

        jdbcClient.sql("update app_user set status = 'CANCELLED' where id = 2")
                .update();
        mockMvc.perform(get("/app/probe")
                        .header("Authorization", "Bearer " + currentToken))
                .andExpect(status().isUnauthorized());
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
        assertNotAuthenticationBlocked("/app/auth/refresh");
        assertNotAuthenticationBlocked("/wxpay/notify");
        assertNotAuthenticationBlocked("/wechat/events");
        assertNotAuthenticationBlocked("/actuator/health");
        assertNotAuthenticationBlocked("/actuator/info");
    }

    @Test
    void removedLocalPublicFileEndpointIsDenied() throws Exception {
        mockMvc.perform(get("/files/public/health-probe.png"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void methodSecurityAccessDeniedReturnsJsonForbiddenEnvelope() throws Exception {
        String token = limitedAdminToken();

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
        long authVersion = jdbcClient.sql("select auth_version from admin_user where id = 1")
                .query(Long.class)
                .single();
        TokenSession session = TokenSession.admin(
                1L,
                "admin",
                roles,
                permissions,
                authVersion,
                Instant.now()
        );
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }

    private String appToken() {
        int updated = jdbcClient.sql("""
                        update app_user
                        set openid = 'security-app-user-2',
                            status = 'ENABLED',
                            auth_version = 0,
                            cancelled_at = null
                        where id = 2
                        """)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            insert into app_user(id, openid, status, auth_version)
                            values(2, 'security-app-user-2', 'ENABLED', 0)
                            """)
                    .update();
        }
        TokenSession session = TokenSession.app(2L, "openid***", 0L, Instant.now());
        return opaqueTokenService.issue(TokenKind.APP, session).accessToken();
    }

    private String limitedAdminToken() {
        long userId = 990_010L;
        long roleId = 990_010L;
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:userId, 'SecurityLimited', :passwordHash, 'Security Limited',
                             'security-limited@shop.local', 'ENABLED')
                        """)
                .param("userId", userId)
                .param("passwordHash", passwordHash)
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:roleId, 'R_MANAGER', 'Manager', '', true)
                        """)
                .param("roleId", roleId)
                .update();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:userId, :roleId)
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();

        TokenSession session = TokenSession.admin(
                userId,
                "SecurityLimited",
                List.of("R_MANAGER"),
                List.of(),
                Instant.now()
        );
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }

    private long logCount(String requestId) {
        return jdbcClient.sql("""
                        select count(*)
                        from admin_system_log
                        where request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(Long.class)
                .single();
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
