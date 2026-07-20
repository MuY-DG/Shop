package org.muybaby.shopserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shop.auth.admin-login-protection.enabled=true",
        "shop.auth.admin-login-protection.store=memory",
        "shop.auth.admin-login-protection.failure-window=15m",
        "shop.auth.admin-login-protection.pair-failure-limit=5",
        "shop.auth.admin-login-protection.account-failure-limit=20",
        "shop.auth.admin-login-protection.ip-failure-limit=100",
        "shop.auth.admin-login-protection.lock-duration=15m",
        "shop.auth.admin-login-protection.memory-max-entries=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminLoginProtectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void unknownDisabledAndWrongPasswordRemainIndistinguishable() throws Exception {
        insertDisabledAdminUser();

        assertInvalid("MissingProtectionUser", "bad", "198.51.100.10");
        assertInvalid("DisabledProtectionUser", "123456", "198.51.100.11");
        assertInvalid("Super", "bad", "198.51.100.12");
    }

    @Test
    void unknownIdentifierIsTemporarilyLockedAtTheSamePairThreshold() throws Exception {
        for (int attempt = 1; attempt < 5; attempt++) {
            assertInvalid("AlwaysMissingProtectionUser", "bad", "198.51.100.20");
        }

        login("AlwaysMissingProtectionUser", "bad", "198.51.100.20")
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value(100005))
                .andExpect(jsonPath("$.msg").value("Too many login attempts; try again later"));
    }

    @Test
    void successfulLoginClearsAccountAndPairFailureSequences() throws Exception {
        assertSuccess("Super", "123456", "198.51.100.30");
        for (int attempt = 1; attempt < 5; attempt++) {
            assertInvalid("Super", "bad", "198.51.100.30");
        }

        assertSuccess("Super", "123456", "198.51.100.30");

        for (int attempt = 1; attempt < 5; attempt++) {
            assertInvalid("Super", "bad", "198.51.100.30");
        }
        login("Super", "bad", "198.51.100.30")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(100005));
    }

    private void assertInvalid(String username, String password, String clientIp) throws Exception {
        login(username, password, clientIp)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002))
                .andExpect(jsonPath("$.msg").value("Invalid username or password"));
    }

    private void assertSuccess(String username, String password, String clientIp) throws Exception {
        login(username, password, clientIp)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private org.springframework.test.web.servlet.ResultActions login(
            String username,
            String password,
            String clientIp
    ) throws Exception {
        String body = "{\"userName\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MockHttpServletRequestBuilder request = post("/admin/auth/login")
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr("127.0.0.1");
                    return servletRequest;
                })
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(request);
    }

    private void insertDisabledAdminUser() {
        jdbcClient.sql("delete from admin_user where id = :id")
                .param("id", 198L)
                .update();
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user (id, username, password_hash, display_name, email, status)
                        values (:id, :username, :passwordHash, :displayName, :email, 'DISABLED')
                        """)
                .param("id", 198L)
                .param("username", "DisabledProtectionUser")
                .param("passwordHash", passwordHash)
                .param("displayName", "Disabled Protection User")
                .param("email", "disabled-protection@shop.local")
                .update();
    }
}
