package org.muybaby.shopserver.admin.rbac.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminRbacServiceTest {

    @Autowired
    private AdminRbacService adminRbacService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void roleAndPermissionQueriesIgnoreDisabledUsers() {
        jdbcClient.sql("update admin_user set status = 'DISABLED' where id = :userId")
                .param("userId", 1L)
                .update();

        assertThat(adminRbacService.roleCodesByUserId(1L)).isEmpty();
        assertThat(adminRbacService.permissionMarksByUserId(1L)).isEmpty();
    }
}
