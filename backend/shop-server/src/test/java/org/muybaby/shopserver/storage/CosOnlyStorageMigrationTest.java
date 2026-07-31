package org.muybaby.shopserver.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CosOnlyStorageMigrationTest {

    @Test
    void migrationRefusesToSilentlyOrphanExistingLocalAssets() throws Exception {
        String databaseName = "cos_only_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Map<String, String> placeholders = Map.of(
                "seed_super_password_hash",
                "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i",
                "seed_super_status",
                "DISABLED"
        );

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(placeholders)
                .target("68")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into storage_asset
                        (scope, media_kind, visibility, provider, storage_container, object_key,
                         original_filename, content_type, extension, size_bytes,
                         uploaded_by_type, uploaded_by_id)
                    values
                        ('LIBRARY', 'IMAGE', 'PUBLIC', 'LOCAL', '/srv/legacy-uploads',
                         'public/library/image/legacy.png', 'legacy.png', 'image/png', 'png', 1,
                         'ADMIN', 1)
                    """);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(placeholders)
                .load();

        assertThatThrownBy(flyway::migrate)
                .hasMessageContaining("V69__cos_only_storage.sql");
    }
}
