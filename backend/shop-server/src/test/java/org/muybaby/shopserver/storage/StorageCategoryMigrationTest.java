package org.muybaby.shopserver.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StorageCategoryMigrationTest {

    @Test
    void unclassifiedFilesAreBackfilledFromTheirPurpose() throws SQLException {
        String databaseName = "storage_category_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target("10")
                .load()
                .migrate();
        seedUnclassifiedFiles(jdbcUrl);

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .load()
                .migrate();

        assertThat(categoryIdsByPurpose(jdbcUrl)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "PRODUCT_IMAGE", 1L,
                "PRODUCT_SKU_IMAGE", 1L,
                "HOME_BANNER", 2L,
                "CATEGORY_ICON", 3L,
                "APP_ICON", 4L,
                "RICH_TEXT_IMAGE", 5L,
                "MARKETING_IMAGE", 6L,
                "AFTER_SALE_IMAGE", 7L,
                "REFUND_EVIDENCE", 7L,
                "PAYMENT_CERTIFICATE", 8L
        ));
    }

    private void seedUnclassifiedFiles(String jdbcUrl) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            int index = 0;
            for (StoragePurpose purpose : StoragePurpose.values()) {
                index++;
                statement.executeUpdate("""
                        insert into storage_file
                            (purpose, asset_category_id, visibility, provider, bucket, object_key,
                             original_filename, content_type, extension, size_bytes, sha256,
                             alt_text, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('%s', null, '%s', 'LOCAL', '', 'test/%d.png',
                             'test-%d.png', 'image/png', 'png', 1, '',
                             '', 'ACTIVE', 'ADMIN', 1)
                        """.formatted(purpose.name(), purpose.visibility().name(), index, index));
            }
        }
    }

    private Map<String, Long> categoryIdsByPurpose(String jdbcUrl) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select purpose, asset_category_id
                     from storage_file
                     order by purpose
                     """)) {
            while (resultSet.next()) {
                result.put(resultSet.getString("purpose"), resultSet.getLong("asset_category_id"));
            }
        }
        return result;
    }
}
