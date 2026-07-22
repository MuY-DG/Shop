package org.muybaby.shopserver.user;

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

class AppUserNicknameMigrationTest {

    @Test
    void v22BackfillsExistingUsersAndAddsANonNullNickname() throws SQLException {
        String databaseName = "app_user_nickname_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway.configure().dataSource(jdbcUrl, "sa", "").target("21").load().migrate();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into app_user (id, openid, status)
                    values (123456789, 'nickname-migration-openid', 'ENABLED')
                    """);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(safeSeedPlaceholders())
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select nickname
                     from app_user
                     where id = 123456789
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("nickname")).isEqualTo("用户456789");
        }
    }

    private Map<String, String> safeSeedPlaceholders() {
        return Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash", "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
    }
}
