package org.muybaby.shopserver.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StorageCustomDomainVerificationMigrationTest {

    @Test
    void preservesLegacyCustomDomainButLeavesItUnverified() throws Exception {
        String databaseName = "storage_domain_verification_"
                + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        migrate(jdbcUrl, "74");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into storage_runtime_setting
                        (id, cos_public_base_url, cos_region, cos_bucket)
                    values
                        (1, 'https://legacy.example.test',
                         'ap-guangzhou', 'shop-assets-1250000000')
                    """);
        }

        migrate(jdbcUrl, "75");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select cos_public_base_url,
                            cos_custom_domain_verification_fingerprint
                     from storage_runtime_setting
                     where id = 1
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("cos_public_base_url"))
                    .isEqualTo("https://legacy.example.test");
            assertThat(resultSet.getString(
                    "cos_custom_domain_verification_fingerprint")).isNull();
            assertThat(resultSet.next()).isFalse();
        }
    }

    private void migrate(String jdbcUrl, String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target(target)
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash",
                        "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
                ))
                .load()
                .migrate();
    }
}
