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

class WechatServiceCardConfigRbacMigrationTest {

    private static final long CUSTOM_ROLE_ID = 99002L;

    @Test
    void configPermissionsRemainSuperOnlyWithoutChangingExistingServiceCardReaders()
            throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:wechat_service_card_config_rbac_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        migrate(jdbcUrl, "100");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into admin_role (id, code, name, description, enabled)
                    values (99002, 'R_SERVICE_CARD_READER', 'Service card reader', '', true)
                    """);
            statement.executeUpdate("""
                    insert into admin_role_menu (role_id, menu_id)
                    values (99002, 800), (99002, 806)
                    """);
            statement.executeUpdate("""
                    insert into admin_role_permission (role_id, permission_id)
                    select 99002, id from admin_permission
                    where auth_mark = 'wechat-service-card:read'
                    """);
        }

        migrate(jdbcUrl, "101");

        assertThat(permissionCount(
                jdbcUrl, CUSTOM_ROLE_ID, "wechat-service-card:config:read")).isZero();
        assertThat(permissionCount(
                jdbcUrl, CUSTOM_ROLE_ID, "wechat-service-card:config:write")).isZero();
        assertThat(permissionCount(
                jdbcUrl, 1L, "wechat-service-card:config:read")).isOne();
        assertThat(permissionCount(
                jdbcUrl, 1L, "wechat-service-card:config:write")).isOne();
    }

    private void migrate(String jdbcUrl, String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target(target)
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash",
                        "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"))
                .load()
                .migrate();
    }

    private int permissionCount(String jdbcUrl, long roleId, String authMark)
            throws SQLException {
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
}
