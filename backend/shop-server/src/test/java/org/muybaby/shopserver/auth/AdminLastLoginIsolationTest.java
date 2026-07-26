package org.muybaby.shopserver.auth;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.log.service.AdminSystemLogRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminLastLoginIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private AdminSystemLogRecorder logRecorder;

    @Test
    void auditPersistenceFailureDoesNotRollbackLastLoginOrFailAuthentication() throws Exception {
        jdbcClient.sql("update admin_user set last_login_at = null where id = 1").update();
        doThrow(new DataAccessResourceFailureException("audit-database-private-detail"))
                .when(logRecorder)
                .record(any());

        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Request-Id", "last-login-audit-isolation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.token", startsWith("adm_")));

        assertThat(jdbcClient.sql("select last_login_at from admin_user where id = 1")
                .query(String.class)
                .single()).isNotBlank();
    }
}
