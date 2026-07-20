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

class SeededAdminSecurityMigrationTest {

    private static final String SAFE_PASSWORD_HASH =
            "$2a$10$dSCU.t56l8Z7MPya89bXnuiMIjScayWL.KeTgc92TqlfLu.woUoYm";

    @Test
    void knownSeedCredentialIsDisabledAndReplaced() throws SQLException {
        String jdbcUrl = jdbcUrl("known_seed");

        migrateTo(jdbcUrl, "36");

        AdminCredential credential = credential(jdbcUrl);
        assertThat(credential.status()).isEqualTo("DISABLED");
        assertThat(credential.passwordHash()).isEqualTo(SAFE_PASSWORD_HASH);
    }

    @Test
    void alreadyRotatedCredentialIsPreserved() throws SQLException {
        String jdbcUrl = jdbcUrl("rotated_seed");
        String rotatedHash = "$2a$10$already.rotated.password.hash.for.test.only";

        migrateTo(jdbcUrl, "35");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    update admin_user
                    set password_hash = '%s'
                    where id = 1
                    """.formatted(rotatedHash));
        }

        migrateTo(jdbcUrl, "36");

        AdminCredential credential = credential(jdbcUrl);
        assertThat(credential.status()).isEqualTo("ENABLED");
        assertThat(credential.passwordHash()).isEqualTo(rotatedHash);
    }

    private void migrateTo(String jdbcUrl, String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target(target)
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash", SAFE_PASSWORD_HASH
                ))
                .load()
                .migrate();
    }

    private AdminCredential credential(String jdbcUrl) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select password_hash, status
                     from admin_user
                     where id = 1
                     """)) {
            assertThat(resultSet.next()).isTrue();
            return new AdminCredential(resultSet.getString("password_hash"), resultSet.getString("status"));
        }
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private record AdminCredential(String passwordHash, String status) {
    }
}
