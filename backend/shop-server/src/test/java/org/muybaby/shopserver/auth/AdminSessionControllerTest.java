package org.muybaby.shopserver.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSessionControllerTest {

    private static final long TARGET_USER_ID = 991L;
    private static final String MAC_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36";
    private static final String WINDOWS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void resetSessionsAndPolicy() {
        opaqueTokenService.revokeSubjectSessions(TokenKind.ADMIN, 1L);
        opaqueTokenService.revokeSubjectSessions(TokenKind.ADMIN, TARGET_USER_ID);
        jdbcClient.sql("""
                        UPDATE admin_user
                        SET max_sessions = 0,
                            auth_version = 1
                        WHERE id = 1
                        """)
                .update();
        deleteTargetUser();
    }

    @AfterEach
    void cleanUp() {
        opaqueTokenService.revokeSubjectSessions(TokenKind.ADMIN, 1L);
        opaqueTokenService.revokeSubjectSessions(TokenKind.ADMIN, TARGET_USER_ID);
        jdbcClient.sql("""
                        UPDATE admin_user
                        SET max_sessions = 0,
                            auth_version = 1
                        WHERE id = 1
                        """)
                .update();
        deleteTargetUser();
    }

    @Test
    void currentAccountCanInspectRevokeAndLogoutAllDevices() throws Exception {
        LoginTokens first = login("Super", "123456", "device-aaaaaaaaaaaaaaaa", MAC_USER_AGENT);
        LoginTokens second = login("Super", "123456", "device-bbbbbbbbbbbbbbbb", WINDOWS_USER_AGENT);

        JsonNode sessions = sessions(second.accessToken(), "/admin/auth/sessions");
        assertThat(sessions.size()).isEqualTo(2);
        JsonNode current = StreamSupport.stream(sessions.spliterator(), false)
                .filter(node -> node.path("current").asBoolean())
                .findFirst()
                .orElseThrow();
        JsonNode other = StreamSupport.stream(sessions.spliterator(), false)
                .filter(node -> !node.path("current").asBoolean())
                .findFirst()
                .orElseThrow();
        assertThat(current.path("deviceName").asText()).isEqualTo("Windows 电脑");
        assertThat(current.path("browser").asText()).isEqualTo("Chrome");
        assertThat(other.path("deviceName").asText()).isEqualTo("Mac");

        mockMvc.perform(delete("/admin/auth/sessions/{sessionId}", other.path("sessionId").asText())
                        .header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk());

        assertAccessRejected(first.accessToken());
        assertRefreshRejected(first.refreshToken());
        assertAccessAccepted(second.accessToken());

        mockMvc.perform(post("/admin/auth/logout-all")
                        .header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk());

        assertAccessRejected(second.accessToken());
        assertRefreshRejected(second.refreshToken());
    }

    @Test
    void singleDevicePolicyKeepsTheNewestDeviceOnly() throws Exception {
        jdbcClient.sql("UPDATE admin_user SET max_sessions = 1 WHERE id = 1").update();

        LoginTokens first = login("Super", "123456", "device-cccccccccccccccc", MAC_USER_AGENT);
        LoginTokens second = login("Super", "123456", "device-dddddddddddddddd", WINDOWS_USER_AGENT);

        assertAccessRejected(first.accessToken());
        assertRefreshRejected(first.refreshToken());
        assertAccessAccepted(second.accessToken());
        assertThat(sessions(second.accessToken(), "/admin/auth/sessions").size()).isEqualTo(1);
    }

    @Test
    void passwordResetAndDisableInvalidateEveryHistoricalSession() throws Exception {
        insertTargetUser();
        LoginTokens superAdmin = login("Super", "123456", "device-super-aaaaaaaa", MAC_USER_AGENT);
        LoginTokens beforePasswordReset = login(
                "SessionTarget",
                "123456",
                "device-target-aaaaaaa",
                MAC_USER_AGENT
        );
        JsonNode managedSessions = sessions(
                superAdmin.accessToken(),
                "/admin/system/users/" + TARGET_USER_ID + "/sessions"
        );
        assertThat(managedSessions.size()).isEqualTo(1);
        assertThat(managedSessions.get(0).path("current").asBoolean()).isFalse();

        mockMvc.perform(put("/admin/system/users/{userId}", TARGET_USER_ID)
                        .header("Authorization", "Bearer " + superAdmin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"Session Target",
                                  "email":"session-target@shop.local",
                                  "password":"654321",
                                  "avatar":"",
                                  "status":"ENABLED",
                                  "roleIds":[2],
                                  "maxSessions":0
                                }
                                """))
                .andExpect(status().isOk());

        assertAccessRejected(beforePasswordReset.accessToken());
        assertRefreshRejected(beforePasswordReset.refreshToken());

        LoginTokens beforeDisable = login(
                "SessionTarget",
                "654321",
                "device-target-bbbbbbb",
                WINDOWS_USER_AGENT
        );
        mockMvc.perform(delete("/admin/system/users/{userId}", TARGET_USER_ID)
                        .header("Authorization", "Bearer " + superAdmin.accessToken()))
                .andExpect(status().isOk());

        assertAccessRejected(beforeDisable.accessToken());
        jdbcClient.sql("UPDATE admin_user SET status = 'ENABLED' WHERE id = :userId")
                .param("userId", TARGET_USER_ID)
                .update();
        assertAccessRejected(beforeDisable.accessToken());
        assertRefreshRejected(beforeDisable.refreshToken());
    }

    @Test
    void administratorCanManageSessionsOnlyThroughAuthorizedSubjectScope() throws Exception {
        insertTargetUser();
        LoginTokens superAdmin = login(
                "Super",
                "123456",
                "device-super-bbbbbbbb",
                MAC_USER_AGENT
        );
        LoginTokens targetMac = login(
                "SessionTarget",
                "123456",
                "device-target-ccccccc",
                MAC_USER_AGENT
        );
        LoginTokens targetWindows = login(
                "SessionTarget",
                "123456",
                "device-target-dddddddd",
                WINDOWS_USER_AGENT
        );

        mockMvc.perform(get("/admin/system/users/{userId}/sessions", 1L)
                        .header("Authorization", "Bearer " + targetWindows.accessToken()))
                .andExpect(status().isForbidden());

        JsonNode managedSessions = sessions(
                superAdmin.accessToken(),
                "/admin/system/users/" + TARGET_USER_ID + "/sessions"
        );
        String macSessionId = StreamSupport.stream(managedSessions.spliterator(), false)
                .filter(node -> "Mac".equals(node.path("deviceName").asText()))
                .map(node -> node.path("sessionId").asText())
                .findFirst()
                .orElseThrow();

        mockMvc.perform(delete("/admin/system/users/{userId}/sessions/{sessionId}", 1L, macSessionId)
                        .header("Authorization", "Bearer " + superAdmin.accessToken()))
                .andExpect(status().isOk());
        assertAccessAccepted(targetMac.accessToken());

        mockMvc.perform(delete(
                                "/admin/system/users/{userId}/sessions/{sessionId}",
                                TARGET_USER_ID,
                                macSessionId
                        )
                        .header("Authorization", "Bearer " + superAdmin.accessToken()))
                .andExpect(status().isOk());
        assertAccessRejected(targetMac.accessToken());
        assertAccessAccepted(targetWindows.accessToken());

        mockMvc.perform(post("/admin/system/users/{userId}/logout-all", TARGET_USER_ID)
                        .header("Authorization", "Bearer " + superAdmin.accessToken()))
                .andExpect(status().isOk());

        assertAccessRejected(targetWindows.accessToken());
        assertRefreshRejected(targetWindows.refreshToken());
        assertAccessAccepted(superAdmin.accessToken());
    }

    private JsonNode sessions(String accessToken, String path) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private LoginTokens login(String username, String password, String deviceId, String userAgent) throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Device-Id", deviceId)
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "userName", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new LoginTokens(data.path("token").asText(), data.path("refreshToken").asText());
    }

    private void assertAccessAccepted(String accessToken) throws Exception {
        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    private void assertAccessRejected(String accessToken) throws Exception {
        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        mockMvc.perform(post("/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "refreshToken", refreshToken
                        ))))
                .andExpect(status().isUnauthorized());
    }

    private void insertTargetUser() {
        String passwordHash = jdbcClient.sql("SELECT password_hash FROM admin_user WHERE id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO admin_user
                            (id, username, password_hash, display_name, email, status, max_sessions, auth_version)
                        VALUES
                            (:id, 'SessionTarget', :passwordHash, 'Session Target',
                             'session-target@shop.local', 'ENABLED', 0, 0)
                        """)
                .param("id", TARGET_USER_ID)
                .param("passwordHash", passwordHash)
                .update();
        jdbcClient.sql("INSERT INTO admin_user_role (user_id, role_id) VALUES (:userId, 2)")
                .param("userId", TARGET_USER_ID)
                .update();
    }

    private void deleteTargetUser() {
        jdbcClient.sql("DELETE FROM admin_user_role WHERE user_id = :userId")
                .param("userId", TARGET_USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM admin_user WHERE id = :userId")
                .param("userId", TARGET_USER_ID)
                .update();
    }

    private record LoginTokens(String accessToken, String refreshToken) {
    }
}
