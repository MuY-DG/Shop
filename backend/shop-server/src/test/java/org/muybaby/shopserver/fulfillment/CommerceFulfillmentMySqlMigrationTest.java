package org.muybaby.shopserver.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CommerceFulfillmentMySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> CLEAN_MYSQL = mysql("fulfillment_clean");

    @Container
    private static final MySQLContainer<?> UPGRADE_MYSQL = mysql("fulfillment_upgrade");

    @Test
    void cleanMySqlSchemaMigratesFromV1ToLatest() throws SQLException {
        CommerceFulfillmentMigrationTest.migrateToLatest(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());

        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword())
                .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");

        try (Connection connection = DriverManager.getConnection(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                     from information_schema.columns
                     where table_schema = database()
                       and table_name = 'order_shipment'
                       and column_name in (
                         'logistics_type', 'delivery_mode', 'item_desc',
                         'express_company_code', 'express_company_name',
                         'consignor_contact', 'receiver_contact',
                         'upload_time', 'last_attempt_at', 'wechat_provider_mode'
                       )
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(10);
        }
    }

    @Test
    void populatedMySqlSchemaMigratesFromV9ToLatestWithoutLosingShipmentEvidence() throws SQLException {
        String jdbcUrl = UPGRADE_MYSQL.getJdbcUrl();
        String username = UPGRADE_MYSQL.getUsername();
        String password = UPGRADE_MYSQL.getPassword();

        CommerceFulfillmentMigrationTest.migrateToV9(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.seedLegacyShipment(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.migrateToLatest(jdbcUrl, username, password);

        CommerceFulfillmentMigrationTest.assertMigratedLegacyShipment(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.assertMigratedLegacyProduct(jdbcUrl, username, password);
    }

    private static MySQLContainer<?> mysql(String databaseName) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(databaseName)
                .withUsername("shop_test")
                .withPassword("shop_test");
    }
}
