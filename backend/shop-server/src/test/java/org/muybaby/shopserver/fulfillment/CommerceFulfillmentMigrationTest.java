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
        assertMigratedLegacyProduct(jdbcUrl, "sa", "");
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
            statement.executeUpdate("""
                    insert into product_category (id, parent_id, name, icon, sort_order, status)
                    values (99004, 0, 'Legacy Product Category', '', 1, 'ENABLED')
                    """);
            statement.executeUpdate("""
                    insert into product_spu
                        (id, category_id, title, subtitle, main_image, selling_points,
                         detail_html, sort_order, status, created_at, updated_at)
                    values
                        (99005, 99004, 'Legacy Product', 'Legacy Subtitle',
                         'https://example.test/legacy-product.jpg', 'Legacy Point',
                         '<p>Legacy Detail</p>', 7, 'ON_SALE', current_timestamp, current_timestamp)
                    """);
            statement.executeUpdate("""
                    insert into product_sku
                        (id, spu_id, sku_code, spec_json, spec_text, price_cent,
                         original_price_cent, stock_available, weight_gram, image,
                         status, sort_order, created_at, updated_at)
                    values
                        (99006, 99005, 'LEGACY-PRODUCT-SKU-A', '{"规格":"A"}', 'A',
                         2980, 3980, 8, 300, 'https://example.test/legacy-sku-a.jpg',
                         'DISABLED', 1, current_timestamp, current_timestamp),
                        (99007, 99005, 'LEGACY-PRODUCT-SKU-B', '{"规格":"B"}', 'B',
                         3280, 4280, 5, 500, 'https://example.test/legacy-sku-b.jpg',
                         'ENABLED', 2, current_timestamp, current_timestamp)
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

    static void assertMigratedLegacyProduct(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("""
                    select id, title, subtitle, main_image, selling_points, detail_html,
                           sort_order, status, spec_type, freight_template_id, virtual_sales, deleted_at, purged_at
                    from product_spu
                    where id = 99005
                    """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong("id")).isEqualTo(99005L);
                assertThat(resultSet.getString("title")).isEqualTo("Legacy Product");
                assertThat(resultSet.getString("subtitle")).isEqualTo("Legacy Subtitle");
                assertThat(resultSet.getString("main_image")).isEqualTo("https://example.test/legacy-product.jpg");
                assertThat(resultSet.getString("selling_points")).isEqualTo("Legacy Point");
                assertThat(resultSet.getString("detail_html")).isEqualTo("<p>Legacy Detail</p>");
                assertThat(resultSet.getInt("sort_order")).isEqualTo(7);
                assertThat(resultSet.getString("status")).isEqualTo("ON_SALE");
                assertThat(resultSet.getString("spec_type")).isEqualTo("MULTI");
                assertThat(resultSet.getLong("freight_template_id")).isEqualTo(1L);
                assertThat(resultSet.getLong("virtual_sales")).isZero();
                assertThat(resultSet.getTimestamp("deleted_at")).isNull();
                assertThat(resultSet.getTimestamp("purged_at")).isNull();
                assertThat(resultSet.next()).isFalse();
            }

            try (ResultSet resultSet = statement.executeQuery("""
                    select id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                           stock_available, weight_gram, image, status, sort_order,
                           combination_key, is_default, cost_price_cent, volume_cubic_meter, deleted_at
                    from product_sku
                    where spu_id = 99005
                    order by id
                    """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong("id")).isEqualTo(99006L);
                assertThat(resultSet.getString("sku_code")).isEqualTo("LEGACY-PRODUCT-SKU-A");
                assertThat(resultSet.getString("spec_json")).isEqualTo("{\"规格\":\"A\"}");
                assertThat(resultSet.getString("spec_text")).isEqualTo("A");
                assertThat(resultSet.getLong("price_cent")).isEqualTo(2980L);
                assertThat(resultSet.getLong("original_price_cent")).isEqualTo(3980L);
                assertThat(resultSet.getInt("stock_available")).isEqualTo(8);
                assertThat(resultSet.getInt("weight_gram")).isEqualTo(300);
                assertThat(resultSet.getString("image")).isEqualTo("https://example.test/legacy-sku-a.jpg");
                assertThat(resultSet.getString("status")).isEqualTo("DISABLED");
                assertThat(resultSet.getInt("sort_order")).isEqualTo(1);
                assertThat(resultSet.getString("combination_key")).isEqualTo("legacy-99006");
                assertThat(resultSet.getBoolean("is_default")).isFalse();
                assertThat(resultSet.getObject("cost_price_cent")).isNull();
                assertThat(resultSet.getBigDecimal("volume_cubic_meter")).isNull();
                assertThat(resultSet.getTimestamp("deleted_at")).isNull();

                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong("id")).isEqualTo(99007L);
                assertThat(resultSet.getString("combination_key")).isEqualTo("legacy-99007");
                assertThat(resultSet.getBoolean("is_default")).isTrue();
                assertThat(resultSet.getInt("weight_gram")).isEqualTo(500);
                assertThat(resultSet.next()).isFalse();
            }

            try (ResultSet resultSet = statement.executeQuery("""
                    select count(*) as total_count,
                           count(distinct combination_key) as unique_combination_count,
                           sum(case when is_default then 1 else 0 end) as default_count
                    from product_sku
                    where spu_id = 99005
                    """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("total_count")).isEqualTo(2);
                assertThat(resultSet.getInt("unique_combination_count")).isEqualTo(2);
                assertThat(resultSet.getInt("default_count")).isEqualTo(1);
            }

            try (ResultSet resultSet = statement.executeQuery("""
                    select name, charge_mode, fixed_amount_cent, status
                    from freight_template
                    where id = 1
                    """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("name")).isEqualTo("全国包邮");
                assertThat(resultSet.getString("charge_mode")).isEqualTo("FREE");
                assertThat(resultSet.getLong("fixed_amount_cent")).isZero();
                assertThat(resultSet.getString("status")).isEqualTo("ENABLED");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }
}
