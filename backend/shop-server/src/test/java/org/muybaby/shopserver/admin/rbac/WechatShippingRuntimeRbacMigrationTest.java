package org.muybaby.shopserver.admin.rbac;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WechatShippingRuntimeRbacMigrationTest {

    private static final long CUSTOM_ROLE_ID = 99001L;

    @Test
    void runtimePermissionsRemainSuperOnlyWithoutOrphaningExistingShippingRoles() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:wechat_shipping_runtime_rbac_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        migrate(jdbcUrl, "99");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into admin_role (id, code, name, description, enabled)
                    values (99001, 'R_SHIPPING_OPERATOR', 'Shipping operator', '', true)
                    """);
            statement.executeUpdate("""
                    insert into admin_role_menu (role_id, menu_id)
                    values (99001, 830), (99001, 501)
                    """);
            statement.executeUpdate("""
                    insert into admin_role_permission (role_id, permission_id)
                    select 99001, id from admin_permission where auth_mark = 'order:ship'
                    """);
        }

        migrate(jdbcUrl, "100");

        assertThat(permissionCount(jdbcUrl, CUSTOM_ROLE_ID, "wechat-shipping:runtime:read"))
                .isZero();
        assertThat(orphanPermissionCount(jdbcUrl, CUSTOM_ROLE_ID)).isZero();
        assertThat(permissionCount(jdbcUrl, 1L, "wechat-shipping:runtime:read")).isOne();
        assertThat(permissionCount(jdbcUrl, 1L, "wechat-shipping:runtime:write")).isOne();
    }

    private void migrate(String jdbcUrl, String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target(target)
                .placeholders(safeSeedPlaceholders())
                .load()
                .migrate();
    }

    private int permissionCount(String jdbcUrl, long roleId, String authMark) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("""
                     select count(*)
                     from admin_role_permission role_permission
                     join admin_permission permission_item
                       on permission_item.id = role_permission.permission_id
                     where role_permission.role_id = ?
                       and permission_item.auth_mark = ?
                     """)) {
            statement.setLong(1, roleId);
            statement.setString(2, authMark);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private int orphanPermissionCount(String jdbcUrl, long roleId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("""
                     select count(*)
                     from admin_role_permission role_permission
                     where role_permission.role_id = ?
                       and not exists (
                           select 1
                           from admin_role_menu role_menu
                           join admin_menu_permission menu_permission
                             on menu_permission.menu_id = role_menu.menu_id
                            and menu_permission.permission_id = role_permission.permission_id
                           where role_menu.role_id = role_permission.role_id
                       )
                     """)) {
            statement.setLong(1, roleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private Map<String, String> safeSeedPlaceholders() {
        return Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash", "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
    }
}
