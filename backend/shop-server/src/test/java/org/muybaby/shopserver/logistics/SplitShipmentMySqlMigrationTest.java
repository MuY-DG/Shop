package org.muybaby.shopserver.logistics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class SplitShipmentMySqlMigrationTest {

    private static final long ORDER_ID = 10_300_001L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("split_shipment_migration")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    @Test
    void v103BackfillsExistingShipmentAndWaybillItemsAndAllowsMultiplePackages() {
        migrateTo("102");
        JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ));
        seedLegacyShipmentAndWaybill(jdbcClient);

        long shipmentId = jdbcClient.sql(
                        "select id from order_shipment where order_id = :orderId"
                )
                .param("orderId", ORDER_ID)
                .query(Long.class)
                .single();
        long waybillId = jdbcClient.sql(
                        "select id from order_electronic_waybill where order_id = :orderId"
                )
                .param("orderId", ORDER_ID)
                .query(Long.class)
                .single();

        migrateTo("103");

        assertThat(jdbcClient.sql("""
                        select package_no, final_shipment
                        from order_shipment
                        where id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getInt("package_no"), rs.getBoolean("final_shipment")
                ))
                .single()).isEqualTo(Map.entry(1, true));
        assertThat(jdbcClient.sql("""
                        select quantity
                        from order_shipment_item
                        where shipment_id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query(Integer.class)
                .single()).isEqualTo(3);
        assertThat(jdbcClient.sql("""
                        select quantity
                        from order_electronic_waybill_item
                        where electronic_waybill_id = :waybillId
                        """)
                .param("waybillId", waybillId)
                .query(Integer.class)
                .single()).isEqualTo(3);

        jdbcClient.sql("""
                        insert into order_shipment(
                            order_id, package_no, final_shipment,
                            logistics_type, delivery_mode, item_desc,
                            express_company_code, express_company_name, tracking_no,
                            consignor_contact, receiver_contact, shipment_note,
                            shipment_source, electronic_waybill_id,
                            status, wechat_provider_mode, wechat_upload_status,
                            wechat_error_code, wechat_error_message, retry_count,
                            shipped_at, created_at, updated_at)
                        select
                            order_id, 2, true,
                            logistics_type, 2, '第二个包裹',
                            express_company_code, express_company_name, 'SF103000012',
                            consignor_contact, receiver_contact, shipment_note,
                            shipment_source, null,
                            status, wechat_provider_mode, wechat_upload_status,
                            wechat_error_code, wechat_error_message, retry_count,
                            shipped_at, created_at, updated_at
                        from order_shipment
                        where id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .update();
        assertThat(jdbcClient.sql(
                        "select count(*) from order_shipment where order_id = :orderId"
                )
                .param("orderId", ORDER_ID)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(version)
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash",
                        "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
                ))
                .load()
                .migrate();
    }

    private void seedLegacyShipmentAndWaybill(JdbcClient jdbcClient) {
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            product_original_amount_cent, product_amount_cent, coupon_name,
                            coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                            receiver_name, receiver_phone, receiver_address,
                            payment_transaction_id, merchant_trade_no, paid_at, shipped_at,
                            created_at, updated_at)
                        values (
                            :orderId, 'SPLIT-MIGRATION-ORDER', 1, 'SHIPPED', 'CART',
                            'split-migration-order', 300, 300, '', 0, 0, 300, 300,
                            '测试买家', '13800138000', '测试地址',
                            'wx-split-migration', 'mch-split-migration',
                            current_timestamp, current_timestamp,
                            current_timestamp, current_timestamp)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        insert into payment_order(
                            order_id, payment_config_id, out_trade_no, prepay_id,
                            transaction_id, payer_openid, status, amount_cent,
                            expires_at, paid_at, created_at, updated_at)
                        values (
                            :orderId, null, 'mch-split-migration', 'prepay-split-migration',
                            'wx-split-migration', 'openid-split-migration', 'PAID', 300,
                            current_timestamp, current_timestamp,
                            current_timestamp, current_timestamp)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        insert into order_item(
                            order_id, sku_id, spu_id, product_title, product_subtitle,
                            main_image, sku_image, display_image, sku_code, spec_text,
                            original_price_cent, unit_price_cent, quantity, refunded_quantity,
                            line_original_amount_cent, line_amount_cent, created_at)
                        values (
                            :orderId, 1, 1, '迁移商品', '', '', '', '', 'SPLIT-MIGRATION-SKU', '',
                            100, 100, 3, 1, 300, 300, current_timestamp)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        insert into order_shipment(
                            order_id, logistics_type, delivery_mode, item_desc,
                            express_company_code, express_company_name, tracking_no,
                            consignor_contact, receiver_contact, shipment_note,
                            shipment_source, electronic_waybill_id,
                            status, wechat_provider_mode, wechat_upload_status,
                            wechat_error_code, wechat_error_message, retry_count,
                            shipped_at, created_at, updated_at)
                        values (
                            :orderId, 1, 1, '迁移商品 x3',
                            'SF', '顺丰速运', 'SF103000011',
                            '', '', '', 'MANUAL', null,
                            'SHIPPED', 'DISABLED', 'SKIPPED', '', '', 0,
                            current_timestamp, current_timestamp, current_timestamp)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        insert into order_electronic_waybill(
                            order_id, attempt_no, idempotency_key, request_digest,
                            provider_order_id, mode, delivery_id, delivery_name, biz_id,
                            service_type, service_name, status, pending_operation, waybill_id,
                            parcel_count, weight_kg, length_cm, width_cm, height_cm,
                            sender_name, sender_mobile, sender_province, sender_city,
                            sender_district, sender_detail_address,
                            receiver_name, receiver_phone, receiver_province, receiver_city,
                            receiver_district, receiver_detail_address,
                            payment_order_id, payer_openid, created_by)
                        select
                            :orderId, 1, 'split-migration-waybill', :requestDigest,
                            'SPLIT-MIGRATION-PROVIDER', 'PRODUCTION', 'SF', '顺丰速运', '',
                            1, '', 'CONFIRMED', 'NONE', 'SF103000011',
                            1, 1.000, 20.00, 15.00, 10.00,
                            '寄件人', '13900139000', '广东省', '深圳市',
                            '南山区', '测试路1号',
                            '测试买家', '13800138000', '广东省', '深圳市',
                            '南山区', '测试路2号',
                            payment.id, payment.payer_openid, 1
                        from payment_order payment
                        where payment.order_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .param("requestDigest", "a".repeat(64))
                .update();
    }
}
