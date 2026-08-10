package org.muybaby.shopserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminRegistrationSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void registrationDefaultsClosedAndGuestHasNoManagementGrants() {
        assertThat(jdbcClient.sql("""
                        SELECT enabled
                        FROM admin_registration_setting
                        WHERE id = 1
                        """)
                .query(Boolean.class)
                .single()).isFalse();

        assertThat(jdbcClient.sql("""
                        SELECT username_normalized
                        FROM admin_user
                        WHERE id = 1
                        """)
                .query(String.class)
                .single()).isEqualTo("super");

        assertThat(jdbcClient.sql("""
                        SELECT permission_item.auth_mark
                        FROM admin_role_permission role_permission
                        JOIN admin_permission permission_item
                          ON permission_item.id = role_permission.permission_id
                        JOIN admin_role role_item
                          ON role_item.id = role_permission.role_id
                        WHERE role_item.code = 'R_GUEST'
                        """)
                .query(String.class)
                .list()).isEmpty();

        assertThat(jdbcClient.sql("""
                        SELECT role_menu.menu_id
                        FROM admin_role_menu role_menu
                        JOIN admin_role role_item ON role_item.id = role_menu.role_id
                        WHERE role_item.code = 'R_GUEST'
                        ORDER BY role_menu.menu_id
                        """)
                .query(Long.class)
                .list()).containsExactly(860L);
    }
}
