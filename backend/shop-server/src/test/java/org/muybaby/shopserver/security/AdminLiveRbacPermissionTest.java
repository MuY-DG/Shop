package org.muybaby.shopserver.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminLiveRbacPermissionTest {

    private static final long USER_ID = 990_001L;
    private static final long ROLE_ID = 990_001L;
    private static final long PERMISSION_ID = 990_001L;
    private static final String PERMISSION = "test:live-rbac-permission";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void issuedAdminAccessTokenUsesLivePermissionGrantsAndRevocations() throws Exception {
        insertAdminWithoutPermission();
        String accessToken = loginAndExtractAccessToken();

        assertProbeForbidden(accessToken);

        jdbcClient.sql("""
                        insert into admin_role_permission (role_id, permission_id)
                        values (:roleId, :permissionId)
                        """)
                .param("roleId", ROLE_ID)
                .param("permissionId", PERMISSION_ID)
                .update();

        mockMvc.perform(get("/admin/live-rbac-permission-probe")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttons", hasItem(PERMISSION)));

        String accessTokenIssuedWithPermission = loginAndExtractAccessToken();

        jdbcClient.sql("update admin_role set enabled = false where id = :roleId")
                .param("roleId", ROLE_ID)
                .update();
        assertProbeForbidden(accessTokenIssuedWithPermission);

        jdbcClient.sql("update admin_role set enabled = true where id = :roleId")
                .param("roleId", ROLE_ID)
                .update();
        mockMvc.perform(get("/admin/live-rbac-permission-probe")
                        .header("Authorization", bearer(accessTokenIssuedWithPermission)))
                .andExpect(status().isOk());

        jdbcClient.sql("""
                        delete from admin_role_permission
                        where role_id = :roleId and permission_id = :permissionId
                        """)
                .param("roleId", ROLE_ID)
                .param("permissionId", PERMISSION_ID)
                .update();

        assertProbeForbidden(accessTokenIssuedWithPermission);
        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", bearer(accessTokenIssuedWithPermission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttons", not(hasItem(PERMISSION))));

        jdbcClient.sql("update admin_user set status = 'DISABLED' where id = :userId")
                .param("userId", USER_ID)
                .update();

        mockMvc.perform(get("/admin/live-rbac-permission-probe")
                        .header("Authorization", bearer(accessTokenIssuedWithPermission)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    private void insertAdminWithoutPermission() {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:userId, 'LiveRbacAdmin', :passwordHash, 'Live RBAC Admin',
                             'live-rbac-admin@shop.local', 'ENABLED')
                        """)
                .param("userId", USER_ID)
                .param("passwordHash", passwordHash)
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:roleId, 'R_LIVE_RBAC_TEST', 'Live RBAC Test', '', true)
                        """)
                .param("roleId", ROLE_ID)
                .update();
        jdbcClient.sql("""
                        insert into admin_permission (id, auth_mark, title)
                        values (:permissionId, :permission, 'Live RBAC permission')
                        """)
                .param("permissionId", PERMISSION_ID)
                .param("permission", PERMISSION)
                .update();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:userId, :roleId)
                        """)
                .param("userId", USER_ID)
                .param("roleId", ROLE_ID)
                .update();
    }

    private String loginAndExtractAccessToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"LiveRbacAdmin","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private void assertProbeForbidden(String accessToken) throws Exception {
        mockMvc.perform(get("/admin/live-rbac-permission-probe")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        LiveRbacPermissionProbeController liveRbacPermissionProbeController() {
            return new LiveRbacPermissionProbeController();
        }
    }

    @RestController
    static class LiveRbacPermissionProbeController {

        @GetMapping("/admin/live-rbac-permission-probe")
        @PreAuthorize("hasAuthority('" + PERMISSION + "')")
        String probe() {
            return "ok";
        }
    }
}
