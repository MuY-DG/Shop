package org.muybaby.shopserver.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.dto.AdminRegistrationRequest;
import org.muybaby.shopserver.auth.service.AdminRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSelfServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminRegistrationService adminRegistrationService;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        adminRegistrationService.updateSetting(1L, false);
        for (Long userId : createdUserIds) {
            jdbcClient.sql("DELETE FROM admin_user_role WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            jdbcClient.sql("DELETE FROM admin_user WHERE id = :userId")
                    .param("userId", userId)
                    .update();
        }
    }

    @Test
    void profileReadsAndUpdatesOnlyPersistedAccountFields() throws Exception {
        RegisteredAdmin admin = registerAdmin("Profile");
        String token = login(admin.username(), admin.password()).path("token").asText();

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userName").value(admin.username()))
                .andExpect(jsonPath("$.data.displayName").value(admin.username()))
                .andExpect(jsonPath("$.data.email").value(""));

        mockMvc.perform(put("/admin/auth/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"真实店铺访客","email":"visitor@example.test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("真实店铺访客"))
                .andExpect(jsonPath("$.data.email").value("visitor@example.test"));

        assertThat(jdbcClient.sql("""
                        SELECT display_name, email
                        FROM admin_user
                        WHERE id = :userId
                        """)
                .param("userId", admin.userId())
                .query((rs, rowNum) -> List.of(
                        rs.getString("display_name"),
                        rs.getString("email")
                ))
                .single()).containsExactly("真实店铺访客", "visitor@example.test");
    }

    @Test
    void changingPasswordRequiresCurrentPasswordAndRevokesEverySession() throws Exception {
        RegisteredAdmin admin = registerAdmin("Password");
        jdbcClient.sql("UPDATE admin_user SET max_sessions = 0 WHERE id = :userId")
                .param("userId", admin.userId())
                .update();

        JsonNode firstLogin = login(admin.username(), admin.password());
        JsonNode secondLogin = login(admin.username(), admin.password());
        String firstAccessToken = firstLogin.path("token").asText();
        String secondAccessToken = secondLogin.path("token").asText();
        String secondRefreshToken = secondLogin.path("refreshToken").asText();

        mockMvc.perform(put("/admin/auth/password")
                        .header("Authorization", "Bearer " + firstAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-password","newPassword":"new-pass-456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(110011));

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + firstAccessToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/auth/password")
                        .header("Authorization", "Bearer " + firstAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", admin.password(),
                                "newPassword", "new-pass-456"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + firstAccessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + secondAccessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", secondRefreshToken
                        ))))
                .andExpect(status().isUnauthorized());

        loginExpectingBadRequest(admin.username(), admin.password());
        login(admin.username(), "new-pass-456");
    }

    private RegisteredAdmin registerAdmin(String prefix) {
        adminRegistrationService.updateSetting(1L, true);
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
        String password = "guest-pass-123";
        long userId = adminRegistrationService.register(new AdminRegistrationRequest(
                username,
                password
        ));
        createdUserIds.add(userId);
        adminRegistrationService.updateSetting(1L, false);
        return new RegisteredAdmin(userId, username, password);
    }

    private JsonNode login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userName", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private void loginExpectingBadRequest(String username, String password) throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userName", username,
                                "password", password
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));
    }

    private record RegisteredAdmin(long userId, String username, String password) {
    }
}
