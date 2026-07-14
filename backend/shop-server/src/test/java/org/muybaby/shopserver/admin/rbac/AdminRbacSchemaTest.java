package org.muybaby.shopserver.admin.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminRbacSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedSuperAdminHasBcryptPasswordAndSystemMenus() {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where username = 'Super'")
                .query(String.class)
                .single();

        Integer menuCount = jdbcClient.sql("select count(*) from admin_menu where enabled = true")
                .query(Integer.class)
                .single();
        Integer storagePermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in ('asset:upload', 'asset:read', 'asset:delete', 'asset:folder')
                        """)
                .query(Integer.class)
                .single();
        Integer storageRouteCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where path = '/storage/files'
                        """)
                .query(Integer.class)
                .single();
        Integer menuReadPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark = 'system:menu:read'
                        """)
                .query(Integer.class)
                .single();
        Integer obsoleteMenuPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in ('add', 'system:menu:update')
                        """)
                .query(Integer.class)
                .single();

        assertThat(passwordEncoder.matches("123456", passwordHash)).isTrue();
        assertThat(menuCount).isGreaterThanOrEqualTo(5);
        assertThat(storagePermissionCount).isEqualTo(4);
        assertThat(storageRouteCount).isEqualTo(1);
        assertThat(menuReadPermissionCount).isEqualTo(1);
        assertThat(obsoleteMenuPermissionCount).isZero();
    }
}
