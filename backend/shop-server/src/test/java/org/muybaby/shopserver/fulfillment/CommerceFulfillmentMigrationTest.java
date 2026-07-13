package org.muybaby.shopserver.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceFulfillmentMigrationTest {

    @Test
    void populatedH2SchemaMigratesFromV9ToLatestWithoutLosingShipmentEvidence() throws SQLException {
        String databaseName = "fulfillment_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        migrateToV9(jdbcUrl, "sa", "");
        seedLegacyShipment(jdbcUrl, "sa", "");
        migrateToLatest(jdbcUrl, "sa", "");

        assertMigratedLegacyShipment(jdbcUrl, "sa", "");
    }

    static void migrateToV9(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .target("9")
                .load()
                .migrate();
    }

    static void migrateToLatest(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .load()
                .migrate();
    }

    static void seedLegacyShipment(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into shop_order
                        (id, order_no, user_id, status, source, idempotency_key,
                         product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                         freight_cent, payable_amount_cent, paid_amount_cent,
                         receiver_name, receiver_phone, receiver_address,
                         payment_transaction_id, merchant_trade_no, paid_at, created_at, updated_at)
                    values
                        (901, 'FULFILLMENT-LEGACY', 1, 'PAID', 'CART', 'fulfillment-legacy',
                         5980, 5980, 0, 0, 5980, 5980,
                         'Legacy User', '13800000000', 'Legacy Address',
                         'wx-legacy-transaction', 'legacy-merchant-trade',
                         current_timestamp, current_timestamp, current_timestamp)
                    """);
            statement.executeUpdate("""
                    insert into order_item
                        (id, order_id, sku_id, spu_id, product_title, product_subtitle,
                         main_image, sku_image, display_image, sku_code, spec_text,
                         original_price_cent, unit_price_cent, quantity,
                         line_original_amount_cent, line_amount_cent, created_at)
                    values
                        (902, 901, 1, 1, 'Legacy Hotpot Item', '',
                         '', '', '', 'LEGACY-SKU', '300g',
                         5980, 5980, 1, 5980, 5980, current_timestamp)
                    """);
            statement.executeUpdate("""
                    insert into order_shipment
                        (id, order_id, express_company, tracking_no, shipment_note, status,
                         wechat_upload_status, wechat_error_code, wechat_error_message,
                         retry_count, shipped_at, wechat_uploaded_at, created_at, updated_at)
                    values
                        (903, 901, 'Legacy Carrier', 'LEGACY-TRACKING-001',
                         'legacy shipment note', 'SHIPPED', 'UPLOADED', '', '', 0,
                         current_timestamp, current_timestamp, current_timestamp, current_timestamp)
                    """);
        }
    }

    static void assertMigratedLegacyShipment(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select logistics_type, delivery_mode, express_company_name,
                            tracking_no, shipment_note, express_company_code,
                            item_desc, wechat_provider_mode
                     from order_shipment
                     where id = 903
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("logistics_type")).isEqualTo(1);
            assertThat(resultSet.getInt("delivery_mode")).isEqualTo(1);
            assertThat(resultSet.getString("express_company_name")).isEqualTo("Legacy Carrier");
            assertThat(resultSet.getString("tracking_no")).isEqualTo("LEGACY-TRACKING-001");
            assertThat(resultSet.getString("shipment_note")).isEqualTo("legacy shipment note");
            assertThat(resultSet.getString("express_company_code")).isNull();
            assertThat(resultSet.getString("item_desc")).isNotBlank();
            assertThat(resultSet.getString("wechat_provider_mode")).isEqualTo("UNKNOWN");
            assertThat(resultSet.next()).isFalse();
        }
    }
}
