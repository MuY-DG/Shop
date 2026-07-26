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

class StoragePublicBaseUrlMigrationTest {

    @Test
    void backfillsTheLegacyUrlIntoTheActiveProviderColumn() throws Exception {
        assertBackfill(
                "LOCAL",
                "https://pay-dev.muybaby6.icu",
                "https://pay-dev.muybaby6.icu",
                ""
        );
        assertBackfill(
                "TENCENT_COS",
                "https://oss.muybaby6.icu",
                "",
                "https://oss.muybaby6.icu"
        );
    }

    private void assertBackfill(
            String provider,
            String legacyUrl,
            String expectedLocalUrl,
            String expectedCosUrl
    ) throws Exception {
        String databaseName = "storage_urls_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target("55")
                .placeholders(safeSeedPlaceholders())
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into storage_runtime_setting
                        (id, provider, public_base_url, local_root)
                    values
                        (1, '%s', '%s', 'var/uploads')
                    """.formatted(provider, legacyUrl));
        }

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(safeSeedPlaceholders())
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select local_public_base_url, cos_public_base_url
                     from storage_runtime_setting
                     where id = 1
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("local_public_base_url")).isEqualTo(expectedLocalUrl);
            assertThat(resultSet.getString("cos_public_base_url")).isEqualTo(expectedCosUrl);
            assertThat(resultSet.next()).isFalse();
        }
    }

    private Map<String, String> safeSeedPlaceholders() {
        return Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash", "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
    }
}
