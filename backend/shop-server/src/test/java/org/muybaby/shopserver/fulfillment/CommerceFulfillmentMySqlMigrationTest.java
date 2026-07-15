package org.muybaby.shopserver.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.AssetModelMigrationTest;
import org.muybaby.shopserver.user.service.AdminCustomerService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("30");
        AssetModelMigrationTest.assertFinalAssetSchema(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        assertAdminCustomerCouponQueryIsCollationSafe();

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

    private void assertAdminCustomerCouponQueryIsCollationSafe() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        alter table coupon_template
                        modify scope_value varchar(255)
                        character set utf8mb4 collate utf8mb4_unicode_ci not null default ''
                        """)
                .update();
        jdbcClient.sql("""
                        insert into app_user (id, openid, nickname, status)
                        values (990001, 'mysql-customer-query', 'MySQL customer query', 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, status)
                        values (990001, 0, 'MySQL customer query', 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, selling_points, detail_html, status)
                        values
                            (990001, 990001, 'MySQL customer query', '', '', 'ON_SALE')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status)
                        values
                            (990001, 'MySQL customer query', 'MIN_SPEND', 'AMOUNT_OFF', 1000, 100,
                             'PRODUCT', '990001', 10, 0, 1,
                             current_timestamp - interval 1 day,
                             current_timestamp + interval 1 day,
                             'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_coupon (spu_id, coupon_template_id)
                        values (990001, 990001)
                        """)
                .update();

        AdminCustomerService service = new AdminCustomerService(
                jdbcClient,
                new NamedParameterJdbcTemplate(dataSource));

        assertThatCode(() -> service.issuableCouponTemplates(990001L))
                .doesNotThrowAnyException();
        assertThat(service.issuableCouponTemplates(990001L))
                .extracting("id")
                .containsExactly(990001L);
    }

    @Test
    void populatedMySqlSchemaMigratesThroughV16AndTheV17StorageCleanBreak() throws SQLException {
        String jdbcUrl = UPGRADE_MYSQL.getJdbcUrl();
        String username = UPGRADE_MYSQL.getUsername();
        String password = UPGRADE_MYSQL.getPassword();

        CommerceFulfillmentMigrationTest.migrateToV9(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.seedLegacyShipment(jdbcUrl, username, password);
        AssetModelMigrationTest.migrateToV16(jdbcUrl, username, password);
        AssetModelMigrationTest.seedLegacyStorageBindings(jdbcUrl, username, password);
        AssetModelMigrationTest.migrateToLatest(jdbcUrl, username, password);

        CommerceFulfillmentMigrationTest.assertMigratedLegacyShipment(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.assertMigratedLegacyProduct(jdbcUrl, username, password);
        AssetModelMigrationTest.assertFinalAssetSchema(jdbcUrl, username, password);
        AssetModelMigrationTest.assertLegacyBindingsWereCleared(jdbcUrl, username, password);
    }

    private static MySQLContainer<?> mysql(String databaseName) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(databaseName)
                .withUsername("shop_test")
                .withPassword("shop_test");
    }
}
