package org.muybaby.shopserver.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.dto.AdminRegistrationRequest;
import org.muybaby.shopserver.auth.service.AdminRegistrationService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminRegistrationService adminRegistrationService;

    @BeforeEach
    void closeRegistration() {
        adminRegistrationService.updateSetting(1L, false);
    }

    @AfterEach
    void restoreClosedRegistration() {
        adminRegistrationService.updateSetting(1L, false);
    }

    @Test
    void registrationIsPublicButFailsClosedUntilSuperEnablesIt() throws Exception {
        mockMvc.perform(get("/admin/auth/registration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());

        mockMvc.perform(post("/admin/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"closed-guest","password":"guest-pass-123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(110009));

        String superToken = login("Super", "123456").path("token").asText();
        mockMvc.perform(put("/admin/system/registration")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        long guestRoleId = jdbcClient.sql("SELECT id FROM admin_role WHERE code = 'R_GUEST'")
                .query(Long.class)
                .single();
        mockMvc.perform(put("/admin/system/roles/{roleId}/grants", guestRoleId)
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":[100,101],"permissionIds":[]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        String username = uniqueUsername("Guest");
        mockMvc.perform(post("/admin/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminRegistrationRequest(
                                username,
                                "guest-pass-123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber());

        long userId = userId(username);
        assertThat(roleCodes(userId)).containsExactly("R_GUEST");
        assertThat(permissionMarks(userId)).isEmpty();

        JsonNode guestLogin = login(username.toLowerCase(), "guest-pass-123");
        String guestToken = guestLogin.path("token").asText();
        mockMvc.perform(get("/admin/system/menus")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].path").value("/guest"));

        mockMvc.perform(put("/admin/system/registration")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));
    }

    @Test
    void repeatedAndConcurrentCaseVariantsCreateExactlyOneGuest() throws Exception {
        adminRegistrationService.updateSetting(1L, true);
        String username = uniqueUsername("Race");
        List<Callable<RegistrationOutcome>> calls = List.of(
                () -> register(username),
                () -> register(username.toLowerCase())
        );

        List<RegistrationOutcome> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<RegistrationOutcome>> futures = executor.invokeAll(calls);
            for (Future<RegistrationOutcome> future : futures) {
                outcomes.add(future.get());
            }
        }

        assertThat(outcomes).filteredOn(RegistrationOutcome::created).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> outcome.errorCode() == ErrorCode.ADMIN_USERNAME_CONFLICT)
                .hasSize(1);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM admin_user
                        WHERE username_normalized = :usernameNormalized
                        """)
                .param("usernameNormalized", username.toLowerCase())
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    private RegistrationOutcome register(String username) {
        try {
            adminRegistrationService.register(new AdminRegistrationRequest(
                    username,
                    "guest-pass-123"
            ));
            return new RegistrationOutcome(true, null);
        } catch (BusinessException ex) {
            return new RegistrationOutcome(false, ex.errorCode());
        }
    }

    private JsonNode login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "userName", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private long userId(String username) {
        return jdbcClient.sql("""
                        SELECT id
                        FROM admin_user
                        WHERE username_normalized = :usernameNormalized
                        """)
                .param("usernameNormalized", username.toLowerCase())
                .query(Long.class)
                .single();
    }

    private List<String> roleCodes(long userId) {
        return jdbcClient.sql("""
                        SELECT role_item.code
                        FROM admin_user_role user_role
                        JOIN admin_role role_item ON role_item.id = user_role.role_id
                        WHERE user_role.user_id = :userId
                        ORDER BY role_item.id
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    private List<String> permissionMarks(long userId) {
        return jdbcClient.sql("""
                        SELECT permission_item.auth_mark
                        FROM admin_user_role user_role
                        JOIN admin_role_permission role_permission
                          ON role_permission.role_id = user_role.role_id
                        JOIN admin_permission permission_item
                          ON permission_item.id = role_permission.permission_id
                        WHERE user_role.user_id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    private String uniqueUsername(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private record RegistrationOutcome(boolean created, ErrorCode errorCode) {
    }
}
