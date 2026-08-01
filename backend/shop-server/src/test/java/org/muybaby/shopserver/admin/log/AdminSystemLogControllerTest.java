package org.muybaby.shopserver.admin.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.login.AdminLoginGuard;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.error.RateLimitException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSystemLogControllerTest {

    private static final long LIMITED_USER_ID = 990_500L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @MockitoBean
    private AdminLoginGuard adminLoginGuard;

    @Test
    void loginAuditRecordsStableSafeDetailsAndUpdatesLastLogin() throws Exception {
        jdbcClient.sql("update admin_user set last_login_at = null where id = 1").update();

        String successRequestId = requestId("login-success");
        String loginResponse = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", successRequestId)
                        .header("User-Agent", "system-log\u202Etest-agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String accessToken = objectMapper.readTree(loginResponse).path("data").path("token").asText();

        LogRow success = log(successRequestId);
        assertThat(success.type()).isEqualTo("LOGIN");
        assertThat(success.result()).isEqualTo("SUCCESS");
        assertThat(success.level()).isEqualTo("INFO");
        assertThat(success.operatorId()).isEqualTo(1L);
        assertThat(success.operatorName()).isEqualTo("Super");
        assertThat(success.module()).isEqualTo("auth");
        assertThat(success.action()).isEqualTo("login");
        assertThat(success.status()).isEqualTo(200);
        assertThat(success.errorCode()).isEmpty();
        assertThat(success.joined())
                .doesNotContain("123456")
                .doesNotContain(accessToken);
        assertThat(jdbcClient.sql("select last_login_at from admin_user where id = 1")
                .query(String.class)
                .single()).isNotBlank();

        String failureRequestId = requestId("login-failure");
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", failureRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"bad-password-private"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));

        LogRow failure = log(failureRequestId);
        assertThat(failure.type()).isEqualTo("LOGIN");
        assertThat(failure.result()).isEqualTo("FAILURE");
        assertThat(failure.level()).isEqualTo("WARN");
        assertThat(failure.operatorId()).isNull();
        assertThat(failure.operatorName()).isEqualTo("Super");
        assertThat(failure.errorCode()).isEqualTo("100002");
        assertThat(failure.errorMessage()).isEqualTo("Invalid username or password");
        assertThat(failure.joined()).doesNotContain("bad-password-private");
    }

    @Test
    void authenticatedRequestsAreClassifiedAndSensitiveInputsAreNotPersisted() throws Exception {
        String token = login();

        String accessRequestId = requestId("access");
        mockMvc.perform(get("/admin/log-test/access/{id}", 42)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", accessRequestId)
                        .header("X-Private", "private-header-secret")
                        .header("User-Agent", "system-log\u202Etest-agent")
                        .cookie(new Cookie("session", "private-cookie-secret"))
                        .queryParam("secret", "private-query-secret"))
                .andExpect(status().isOk());
        LogRow access = log(accessRequestId);
        assertThat(access.type()).isEqualTo("ACCESS");
        assertThat(access.result()).isEqualTo("SUCCESS");
        assertThat(access.level()).isEqualTo("INFO");
        assertThat(access.path()).isEqualTo("/admin/log-test/access/42");
        assertThat(access.pattern()).isEqualTo("/admin/log-test/access/{id}");
        assertThat(access.userAgent()).isEqualTo("system-log test-agent");
        assertThat(access.joined())
                .doesNotContain("private-header-secret")
                .doesNotContain("private-cookie-secret")
                .doesNotContain("private-query-secret")
                .doesNotContain(token);

        String operationRequestId = requestId("operation");
        mockMvc.perform(post("/admin/log-test/operation")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", operationRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"private":"private-body-secret"}
                                """))
                .andExpect(status().isOk());
        LogRow operation = log(operationRequestId);
        assertThat(operation.type()).isEqualTo("OPERATION");
        assertThat(operation.result()).isEqualTo("SUCCESS");
        assertThat(operation.joined()).doesNotContain("private-body-secret");

        String forbiddenRequestId = requestId("forbidden");
        mockMvc.perform(post("/admin/log-test/forbidden")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", forbiddenRequestId))
                .andExpect(status().isForbidden());
        LogRow forbidden = log(forbiddenRequestId);
        assertThat(forbidden.type()).isEqualTo("OPERATION");
        assertThat(forbidden.result()).isEqualTo("FAILURE");
        assertThat(forbidden.level()).isEqualTo("WARN");
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.errorCode()).isEqualTo("100003");

        String exceptionRequestId = requestId("exception");
        mockMvc.perform(get("/admin/log-test/error")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", exceptionRequestId))
                .andExpect(status().isInternalServerError());
        LogRow exception = log(exceptionRequestId);
        assertThat(exception.type()).isEqualTo("EXCEPTION");
        assertThat(exception.result()).isEqualTo("FAILURE");
        assertThat(exception.level()).isEqualTo("ERROR");
        assertThat(exception.errorCode()).isEqualTo("100500");
        assertThat(exception.errorMessage()).isEqualTo("Internal server error");
        assertThat(exception.joined()).doesNotContain("private-exception-secret");

        String excludedExceptionRequestId = requestId("excluded-exception");
        mockMvc.perform(get("/admin/realtime/tickets/test-error")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", excludedExceptionRequestId))
                .andExpect(status().isInternalServerError());
        LogRow excludedException = log(excludedExceptionRequestId);
        assertThat(excludedException.type()).isEqualTo("EXCEPTION");
        assertThat(excludedException.result()).isEqualTo("FAILURE");
        assertThat(excludedException.level()).isEqualTo("ERROR");
        assertThat(excludedException.errorCode()).isEqualTo("100500");
    }

    @Test
    void anonymousAdminProbesDoNotAmplifyIntoDatabaseLogs() throws Exception {
        String requestId = requestId("anonymous");

        mockMvc.perform(get("/admin/log-test/access/99")
                        .header("X-Request-Id", requestId))
                .andExpect(status().isUnauthorized());

        assertThat(logCount(requestId)).isZero();
    }

    @Test
    void rateLimitedUnavailableAndMalformedLoginsDoNotAmplifyIntoDatabaseLogs() throws Exception {
        when(adminLoginGuard.start(eq("RateLimited"), anyString()))
                .thenThrow(new RateLimitException(ErrorCode.ADMIN_LOGIN_RATE_LIMITED, 900L));
        when(adminLoginGuard.start(eq("Unavailable"), anyString()))
                .thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE));

        String rateLimitedRequestId = requestId("login-rate-limited");
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", rateLimitedRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"RateLimited","password":"private-password"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(100005));
        assertThat(logCount(rateLimitedRequestId)).isZero();

        String unavailableRequestId = requestId("login-unavailable");
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", unavailableRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Unavailable","password":"private-password"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(100503));
        assertThat(logCount(unavailableRequestId)).isZero();

        String malformedRequestId = requestId("login-malformed");
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", malformedRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        assertThat(logCount(malformedRequestId)).isZero();
    }

    @Test
    void logQueryRequiresPermissionSupportsFiltersAndSkipsSuccessfulSelfReads() throws Exception {
        String anonymousQueryRequestId = requestId("query-anonymous");
        mockMvc.perform(get("/admin/system/logs")
                        .header("X-Request-Id", anonymousQueryRequestId))
                .andExpect(status().isUnauthorized());
        assertThat(logCount(anonymousQueryRequestId)).isZero();

        String forbiddenQueryRequestId = requestId("query-forbidden");
        mockMvc.perform(get("/admin/system/logs")
                        .header("X-Request-Id", forbiddenQueryRequestId)
                        .header("Authorization", "Bearer " + limitedAdminToken()))
                .andExpect(status().isForbidden());
        LogRow forbiddenQuery = log(forbiddenQueryRequestId);
        assertThat(forbiddenQuery.type()).isEqualTo("ACCESS");
        assertThat(forbiddenQuery.result()).isEqualTo("FAILURE");
        assertThat(forbiddenQuery.status()).isEqualTo(403);

        String token = login();
        String accessRequestId = requestId("query-source");
        mockMvc.perform(get("/admin/log-test/access/7")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", accessRequestId))
                .andExpect(status().isOk());

        String queryRequestId = requestId("query-self");
        mockMvc.perform(get("/admin/system/logs")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", queryRequestId)
                        .queryParam("current", "1")
                        .queryParam("size", "999")
                        .queryParam("type", "access")
                        .queryParam("result", "success")
                        .queryParam("module", "log-test")
                        .queryParam("operator", "Super")
                        .queryParam("clientIp", "127.0.0.1")
                        .queryParam("requestId", accessRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.records[0].id").isString())
                .andExpect(jsonPath("$.data.records[0].operatorUserId").value("1"))
                .andExpect(jsonPath("$.data.records[0].requestId").value(accessRequestId));
        assertThat(logCount(queryRequestId)).isZero();

        insertSearchLog("Audit_User", requestId("literal-underscore"));
        insertSearchLog("AuditXUser", requestId("wildcard-control"));
        mockMvc.perform(get("/admin/system/logs")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("operator", "Audit_User")
                        .queryParam("module", "LIKE-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].operatorUsername").value("Audit_User"));

        String invalidQueryRequestId = requestId("query-invalid-range");
        mockMvc.perform(get("/admin/system/logs")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", invalidQueryRequestId)
                        .queryParam("occurredStart", "2026-07-27T00:00:00Z")
                        .queryParam("occurredEnd", "2026-07-26T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        LogRow invalidQuery = log(invalidQueryRequestId);
        assertThat(invalidQuery.type()).isEqualTo("ACCESS");
        assertThat(invalidQuery.result()).isEqualTo("FAILURE");
        assertThat(invalidQuery.status()).isEqualTo(400);

        mockMvc.perform(get("/admin/system/logs")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("occurredEnd", "9999-12-31T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        mockMvc.perform(get("/admin/system/logs")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("type", "not-a-type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        mockMvc.perform(get("/admin/system/logs")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("current", Long.toString(Long.MAX_VALUE))
                        .queryParam("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    private String login() throws Exception {
        String requestId = requestId("helper-login");
        String response = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String limitedAdminToken() {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user (
                            id, username, password_hash, display_name, email, status
                        )
                        values (
                            :id, 'LogLimited', :passwordHash, 'Log Limited',
                            'log-limited@shop.local', 'ENABLED'
                        )
                        """)
                .param("id", LIMITED_USER_ID)
                .param("passwordHash", passwordHash)
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:id, 'R_LOG_LIMITED', 'Log Limited', '', true)
                        """)
                .param("id", LIMITED_USER_ID)
                .update();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:id, :id)
                        """)
                .param("id", LIMITED_USER_ID)
                .update();
        return opaqueTokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(
                        LIMITED_USER_ID,
                        "LogLimited",
                        List.of("R_LOG_LIMITED"),
                        List.of(),
                        Instant.now()
                )
        ).accessToken();
    }

    private LogRow log(String requestId) {
        return jdbcClient.sql("""
                        select log_type, result, level, operator_id, operator_name,
                               module, action, request_method, request_path, route_pattern,
                               http_status, client_ip, user_agent, error_code, error_message
                        from admin_system_log
                        where request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new LogRow(
                        rs.getString("log_type"),
                        rs.getString("result"),
                        rs.getString("level"),
                        rs.getObject("operator_id", Long.class),
                        rs.getString("operator_name"),
                        rs.getString("module"),
                        rs.getString("action"),
                        rs.getString("request_method"),
                        rs.getString("request_path"),
                        rs.getString("route_pattern"),
                        rs.getInt("http_status"),
                        rs.getString("client_ip"),
                        rs.getString("user_agent"),
                        rs.getString("error_code"),
                        rs.getString("error_message")
                ))
                .single();
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

    private void insertSearchLog(String operatorName, String requestId) {
        jdbcClient.sql("""
                        insert into admin_system_log (
                            log_type, result, level, operator_name, module, action,
                            request_method, request_path, route_pattern, http_status,
                            duration_ms, client_ip, user_agent, request_id
                        )
                        values (
                            'ACCESS', 'SUCCESS', 'INFO', :operatorName, 'like-test', 'search',
                            'GET', '/admin/log-test/search', '/admin/log-test/search', 200,
                            1, '127.0.0.1', '', :requestId
                        )
                        """)
                .param("operatorName", operatorName)
                .param("requestId", requestId)
                .update();
    }

    private String requestId(String purpose) {
        return "system-log-" + purpose + "-" + UUID.randomUUID();
    }

    private record LogRow(
            String type,
            String result,
            String level,
            Long operatorId,
            String operatorName,
            String module,
            String action,
            String method,
            String path,
            String pattern,
            int status,
            String clientIp,
            String userAgent,
            String errorCode,
            String errorMessage
    ) {
        private String joined() {
            return String.join("|",
                    type,
                    result,
                    level,
                    operatorName,
                    module,
                    action,
                    method,
                    path,
                    pattern,
                    clientIp,
                    userAgent,
                    errorCode,
                    errorMessage
            );
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        ExcludedProbeController excludedProbeController() {
            return new ExcludedProbeController();
        }
    }

    @RestController
    @RequestMapping("/admin/log-test")
    static class ProbeController {

        @GetMapping("/access/{id}")
        ApiResponse<String> access(@PathVariable long id) {
            return ApiResponse.success(Long.toString(id));
        }

        @PostMapping("/operation")
        @PreAuthorize("hasAuthority('system:log:read')")
        ApiResponse<Void> operation() {
            return ApiResponse.success();
        }

        @PostMapping("/forbidden")
        @PreAuthorize("hasAuthority('system:log:test-never')")
        ApiResponse<Void> forbidden() {
            return ApiResponse.success();
        }

        @GetMapping("/error")
        ApiResponse<Void> error() {
            throw new IllegalStateException("private-exception-secret");
        }
    }

    @RestController
    @RequestMapping("/admin/realtime/tickets")
    static class ExcludedProbeController {

        @GetMapping("/test-error")
        ApiResponse<Void> error() {
            throw new IllegalStateException("excluded-endpoint-private-secret");
        }
    }
}
