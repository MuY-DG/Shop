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
import static org.muybaby.shopserver.support.MigrationTestSupport.latestMigrationVersion;

class RbacDisplayTextMigrationTest {

    @Test
    void localizesSystemDisplayTextWithoutOverwritingCustomizedRoleFields() throws SQLException {
        String jdbcUrl = jdbcUrl();
        migrateTo(jdbcUrl, "58");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    update admin_role
                    set name = '总管理员'
                    where code = 'R_SUPER'
                    """);
            statement.executeUpdate("""
                    update admin_role
                    set description = '自定义运营权限'
                    where code = 'R_ADMIN'
                    """);
        }

        migrateTo(jdbcUrl, latestMigrationVersion());

        assertThat(role(jdbcUrl, "R_SUPER"))
                .isEqualTo(new RoleText("总管理员", "拥有系统全部权限"));
        assertThat(role(jdbcUrl, "R_ADMIN"))
                .isEqualTo(new RoleText("商城管理员", "自定义运营权限"));
        assertThat(role(jdbcUrl, "R_CUSTOMER_SERVICE"))
                .isEqualTo(new RoleText("客服", "负责在线客户服务"));
        assertThat(permissionTitle(jdbcUrl, "system:menu:read"))
                .isEqualTo("查看菜单与权限资源");
        assertThat(permissionTitle(jdbcUrl, "asset:read"))
                .isEqualTo("查看素材");
        assertThat(permissionTitle(jdbcUrl, "order:shipping:retry"))
                .isEqualTo("重试微信发货信息上传");
        assertAllPermissionTitlesLocalized(jdbcUrl);
    }

    private void migrateTo(String jdbcUrl, String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target(target)
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash",
                        "$2a$10$dSCU.t56l8Z7MPya89bXnuiMIjScayWL.KeTgc92TqlfLu.woUoYm"
                ))
                .load()
                .migrate();
    }

    private RoleText role(String jdbcUrl, String code) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("""
                     select name, description
                     from admin_role
                     where code = ?
                     """)) {
            statement.setString(1, code);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return new RoleText(resultSet.getString("name"), resultSet.getString("description"));
            }
        }
    }

    private String permissionTitle(String jdbcUrl, String authMark) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("""
                     select title
                     from admin_permission
                     where auth_mark = ?
                     """)) {
            statement.setString(1, authMark);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString("title");
            }
        }
    }

    private void assertAllPermissionTitlesLocalized(String jdbcUrl) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select auth_mark, title
                     from admin_permission
                     order by auth_mark
                     """)) {
            while (resultSet.next()) {
                assertThat(resultSet.getString("title"))
                        .as("permission title for %s", resultSet.getString("auth_mark"))
                        .doesNotContainPattern("[A-Za-z]");
            }
        }
    }

    private String jdbcUrl() {
        return "jdbc:h2:mem:rbac_display_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private record RoleText(String name, String description) {
    }
}
