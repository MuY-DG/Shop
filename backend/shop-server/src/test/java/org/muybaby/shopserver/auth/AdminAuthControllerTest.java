package org.muybaby.shopserver.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginReturnsAdminTokenPair() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("adr_")))
                .andExpect(jsonPath("$.data.expiresIn").value(7200));
    }

    @Test
    void loginRejectsBadPassword() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"bad"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    void loginRejectsUnknownUsernameWithInvalidCredentialsEnvelope() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Missing","password":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    void loginRejectsDisabledUsernameWithInvalidCredentialsEnvelope() throws Exception {
        insertDisabledAdminUser();

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Disabled","password":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    void currentUserReturnsRolesAndButtons() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.userName").value("Super"))
                .andExpect(jsonPath("$.data.roles", containsInAnyOrder("R_SUPER")))
                .andExpect(jsonPath("$.data.buttons", containsInAnyOrder(
                        "add",
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:menu:update"
                )));
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
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

    private void insertDisabledAdminUser() {
        jdbcClient.sql("delete from admin_user where id = :id")
                .param("id", 99L)
                .update();
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = :userId")
                .param("userId", 1L)
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user (id, username, password_hash, display_name, email, status)
                        values (:id, :username, :passwordHash, :displayName, :email, :status)
                        """)
                .param("id", 99L)
                .param("username", "Disabled")
                .param("passwordHash", passwordHash)
                .param("displayName", "Disabled Admin")
                .param("email", "disabled@shop.local")
                .param("status", "DISABLED")
                .update();
    }
}
