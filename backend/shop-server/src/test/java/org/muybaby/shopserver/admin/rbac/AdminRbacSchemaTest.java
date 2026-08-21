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
                        where id = 600
                          and parent_id = 620
                          and path = 'assets'
                          and component = '/storage/files'
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
        Integer customerPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'customer:user:read', 'customer:coupon:issue', 'customer:user:status'
                        )
                        """)
                .query(Integer.class)
                .single();
        Integer customerMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 450
                          and path = '/customers'
                          and component = '/customer/user'
                        """)
                .query(Integer.class)
                .single();
        Integer couponCenterMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where (id = 401 and parent_id = 400 and path = 'coupon' and component = '')
                           or (id = 402 and parent_id = 401 and path = 'templates' and component = '/marketing/coupon')
                           or (id = 403 and parent_id = 401 and path = 'claim-records' and component = '/marketing/coupon-claim')
                        """)
                .query(Integer.class)
                .single();
        Integer couponClaimReadGrantCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_permission p on p.id = rp.permission_id
                        join admin_role r on r.id = rp.role_id
                        where r.code = 'R_SUPER'
                          and p.auth_mark = 'coupon:claim:read'
                        """)
                .query(Integer.class)
                .single();
        Integer operationPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark like 'operation:%:read'
                        """)
                .query(Integer.class)
                .single();
        Integer operationMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id between 100 and 107
                          and (id = 100 or parent_id = 100)
                        """)
                .query(Integer.class)
                .single();

        assertThat(passwordEncoder.matches("123456", passwordHash)).isTrue();
        assertThat(menuCount).isGreaterThanOrEqualTo(5);
        assertThat(storagePermissionCount).isEqualTo(4);
        assertThat(storageRouteCount).isEqualTo(1);
        assertThat(menuReadPermissionCount).isEqualTo(1);
        assertThat(obsoleteMenuPermissionCount).isZero();
        assertThat(customerPermissionCount).isEqualTo(3);
        assertThat(customerMenuCount).isEqualTo(1);
        assertThat(couponCenterMenuCount).isEqualTo(3);
        assertThat(couponClaimReadGrantCount).isEqualTo(1);
        assertThat(operationPermissionCount).isEqualTo(7);
        assertThat(operationMenuCount).isEqualTo(8);
    }

    @Test
    void adminSessionPolicyDefaultsAndPermissionsAreMigrated() {
        String sessionPolicy = jdbcClient.sql("""
                        select concat(max_sessions, '|', auth_version)
                        from admin_user
                        where username = 'Super'
                        """)
                .query(String.class)
                .single();
        Integer sessionPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission permission_item
                        join admin_menu_permission menu_permission
                          on menu_permission.permission_id = permission_item.id
                         and menu_permission.menu_id = 201
                        join admin_role_permission role_permission
                          on role_permission.permission_id = permission_item.id
                        join admin_role role_item
                          on role_item.id = role_permission.role_id
                         and role_item.code = 'R_SUPER'
                        where permission_item.id in (1004, 1005)
                          and permission_item.auth_mark in (
                              'system:user:session:read',
                              'system:user:session:revoke'
                          )
                        """)
                .query(Integer.class)
                .single();

        assertThat(sessionPolicy).isEqualTo("0|1");
        assertThat(sessionPermissionCount).isEqualTo(2);
    }
}
