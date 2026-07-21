package org.muybaby.shopserver.location;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmapMiniProgramMigrationTest {

    @Test
    void v52InvalidatesLegacyWebServiceKeyAndRenamesTheEncryptedColumn() throws SQLException {
        String databaseName = "amap_mini_program_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target("51")
                .placeholders(safeSeedPlaceholders())
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into amap_runtime_setting
                        (id, enabled, web_service_key_ciphertext, secret_revision)
                    values
                        (1, true, 'legacy-web-service-ciphertext', 7)
                    """);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(safeSeedPlaceholders())
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("52");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select enabled, mini_program_key_ciphertext, secret_revision
                     from amap_runtime_setting
                     where id = 1
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBoolean("enabled")).isFalse();
            assertThat(resultSet.getString("mini_program_key_ciphertext")).isEmpty();
            assertThat(resultSet.getLong("secret_revision")).isEqualTo(8L);
            assertThatThrownBy(() -> statement.executeQuery("""
                    select web_service_key_ciphertext
                    from amap_runtime_setting
                    """))
                    .isInstanceOf(SQLException.class);
        }
    }

    private Map<String, String> safeSeedPlaceholders() {
        return Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash", "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
    }
}
