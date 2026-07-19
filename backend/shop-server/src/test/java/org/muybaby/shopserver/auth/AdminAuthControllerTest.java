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
                .andExpect(jsonPath("$.data.userId").isString())
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.userName").value("Super"))
                .andExpect(jsonPath("$.data.roles", containsInAnyOrder("R_SUPER")))
                .andExpect(jsonPath("$.data.buttons", containsInAnyOrder(
                        "system:menu:read",
                        "product:category:create",
                        "product:category:update",
                        "product:spu:create",
                        "product:spu:update",
                        "product:spu:publish",
                        "product:sku:stock",
                        "product:spu:delete",
                        "product:spu:restore",
                        "product:spu:purge",
                        "product:spec-template:create",
                        "product:spec-template:update",
                        "product:guarantee:create",
                        "product:guarantee:update",
                        "product:guarantee:delete",
                        "product:guarantee:visibility",
                        "product:freight:create",
                        "product:freight:update",
                        "product:coupon:bind",
                        "product:coupon:create",
                        "coupon:template:create",
                        "coupon:template:update",
                        "coupon:template:enable",
                        "coupon:template:disable",
                        "coupon:claim:read",
                        "customer:user:read",
                        "customer:coupon:issue",
                        "order:read",
                        "order:close",
                        "order:ship",
                        "order:shipping:retry",
                        "customer-service:conversation:read",
                        "customer-service:conversation:claim",
                        "customer-service:conversation:transfer",
                        "customer-service:conversation:close",
                        "customer-service:message:send",
                        "customer-service:order:link",
                        "customer-service:product:send",
                        "customer-service:agent:manage",
                        "payment:config:read",
                        "payment:config:write",
                        "payment:config:enable",
                        "storage:config:read",
                        "storage:config:write",
                        "aftersale:read",
                        "aftersale:audit",
                        "asset:upload",
                        "asset:read",
                        "asset:delete",
                        "asset:folder",
                        "content:banner:read",
                        "content:banner:create",
                        "content:banner:update",
                        "content:banner:publish",
                        "content:home-category:read",
                        "content:home-category:write",
                        "content:home-hot:read",
                        "content:home-hot:write",
                        "content:home-recommended:read",
                        "content:home-recommended:write",
                        "content:contact:read",
                        "content:contact:write",
                        "operation:overview:read",
                        "operation:trade:read",
                        "operation:product:read",
                        "operation:user:read",
                        "operation:traffic:read",
                        "operation:marketing:read",
                        "operation:service:read",
                        "system:user:read",
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable",
                        "system:role:read",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:role:delete"
                )));
    }

    @Test
    void refreshRotatesAdminTokenPairAndInvalidatesTheOldAccessToken() throws Exception {
        String loginResponse = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String oldAccessToken = objectMapper.readTree(loginResponse).path("data").path("token").asText();
        String refreshToken = objectMapper.readTree(loginResponse).path("data").path("refreshToken").asText();

        mockMvc.perform(post("/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new java.util.HashMap<>(java.util.Map.of(
                                "refreshToken", refreshToken
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("adr_")));

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());
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
