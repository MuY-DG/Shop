package org.muybaby.shopserver.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class AfterSaleFulfillmentMigrationTestSupport {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 2, 12, 0);
    private static final List<LegacyCase> CASES = List.of(
            new LegacyCase(1, "PAID", "REFUND_ONLY", 2, "UNSHIPPED"),
            new LegacyCase(2, "PARTIALLY_SHIPPED", "REFUND_ONLY", 2, "LEGACY_UNKNOWN"),
            new LegacyCase(3, "SHIPPED", "REFUND_ONLY", 2, "LEGACY_UNKNOWN"),
            new LegacyCase(4, "PAID", "REFUND_ONLY", 2, "LEGACY_UNKNOWN"),
            new LegacyCase(5, "PAID", "RETURN_REFUND", 2, "LEGACY_UNKNOWN"),
            new LegacyCase(6, "", "REFUND_ONLY", 2, "LEGACY_UNKNOWN"),
            new LegacyCase(7, "PAID", "REFUND_ONLY", 0, null),
            new LegacyCase(8, "PAID", "REFUND_ONLY", null, null),
            new LegacyCase(9, "PAID", "REFUND_ONLY", 2, "UNSHIPPED"),
            new LegacyCase(10, "PAID", "REFUND_ONLY", 2, "LEGACY_UNKNOWN"),
            new LegacyCase(11, "PAID", "REFUND_ONLY", 2, "LEGACY_UNKNOWN")
    );

    private AfterSaleFulfillmentMigrationTestSupport() {
    }

    static void insertLegacyRows(JdbcClient jdbc) {
        for (LegacyCase scenario : CASES) {
            long orderId = 9_881_900L + scenario.id();
            long orderItemId = 9_882_000L + scenario.id();
            long afterSaleId = 9_882_100L + scenario.id();
            jdbc.sql("""
                            insert into shop_order
                                (id, order_no, user_id, status, idempotency_key, checkout_request_digest)
                            values (:id, :orderNo, 9881800, 'SHIPPED', :orderNo,
                                    'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff')
                            """).param("id", orderId).param("orderNo", "V19-LEGACY-" + scenario.id()).update();
            jdbc.sql("""
                            insert into order_item
                                (id, order_id, sku_id, spu_id, product_title, sku_code, quantity, line_amount_cent)
                            values (:id, :orderId, 9881801, 9881802, 'V19 historical item', :skuCode, 3, 3000)
                            """).param("id", orderItemId).param("orderId", orderId)
                    .param("skuCode", "V19-SKU-" + scenario.id()).update();
            Long approvedAmount = scenario.approvedQuantity() == null ? null : scenario.approvedQuantity() * 1000L;
            jdbc.sql("""
                            insert into after_sale_request
                                (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                                 requested_amount_cent, approved_amount_cent, source_order_status, created_at)
                            values (:id, :afterSaleNo, :orderId, 9881800, :type, 'APPROVED', 'Historical refund',
                                    3000, :amount, :sourceStatus, :createdAt)
                            """).param("id", afterSaleId).param("afterSaleNo", "AS-V19-LEGACY-" + scenario.id())
                    .param("orderId", orderId).param("type", scenario.type()).param("amount", approvedAmount)
                    .param("sourceStatus", scenario.sourceStatus()).param("createdAt", REQUESTED_AT).update();
            jdbc.sql("""
                            insert into after_sale_item
                                (id, after_sale_id, order_item_id, sku_id, order_quantity_snapshot,
                                 paid_amount_basis_cent, requested_quantity, approved_quantity,
                                 requested_amount_cent, approved_amount_cent, restock_quantity, created_at)
                            values (:id, :afterSaleId, :orderItemId, 9881801, 3, 3000, 3, :quantity,
                                    3000, :amount, :restock, :createdAt)
                            """).param("id", 9_882_200L + scenario.id()).param("afterSaleId", afterSaleId)
                    .param("orderItemId", orderItemId).param("quantity", scenario.approvedQuantity())
                    .param("amount", approvedAmount)
                    .param("restock", approvedAmount != null && approvedAmount > 0 ? 1 : 0)
                    .param("createdAt", REQUESTED_AT).update();
        }
        insertShipment(jdbc, 4, REQUESTED_AT.minusSeconds(1));
        insertShipment(jdbc, 9, REQUESTED_AT.plusSeconds(1));
        insertShipment(jdbc, 10, null);
        insertShipment(jdbc, 11, REQUESTED_AT);
    }

    static void assertConservativeHistory(JdbcClient jdbc) {
        for (LegacyCase scenario : CASES) {
            var sources = jdbc.sql("""
                            select source_type, shipment_item_id, quantity, created_at
                            from after_sale_fulfillment_allocation where after_sale_item_id = :id
                            """).param("id", 9_882_200L + scenario.id())
                    .query((rs, rowNum) -> new Allocation(
                            rs.getString("source_type"), rs.getLong("shipment_item_id"), rs.getInt("quantity"),
                            rs.getObject("created_at", LocalDateTime.class))).list();
            if (scenario.expectedSource() == null) {
                assertThat(sources).as("Unapproved or zero-approved case %s", scenario.id()).isEmpty();
            } else {
                assertThat(sources).as("Historical case %s", scenario.id()).containsExactly(
                        new Allocation(scenario.expectedSource(), 0L, scenario.approvedQuantity(), REQUESTED_AT));
            }
        }
        assertThat(jdbc.sql("""
                        select count(*) from after_sale_item
                        where id between 9882201 and 9882211 and received_quantity is null
                        """).query(Long.class).single()).isEqualTo((long) CASES.size());
        assertThat(jdbc.sql("""
                        select count(*) from after_sale_item
                        where id between 9882201 and 9882211
                          and approved_quantity > 0 and restock_quantity = 1
                        """).query(Long.class).single()).isEqualTo(9L);
        assertThat(jdbc.sql("""
                        select count(*) from order_shipment
                        where order_id between 9881901 and 9881911 and wechat_upload_refresh_pending = false
                        """).query(Long.class).single()).isEqualTo(4L);
    }

    private static void insertShipment(JdbcClient jdbc, int scenarioId, LocalDateTime shippedAt) {
        jdbc.sql("""
                        insert into order_shipment (order_id, status, wechat_upload_status, shipped_at, created_at)
                        values (:orderId, 'SHIPPED', 'UPLOADED', :shippedAt, :createdAt)
                        """).param("orderId", 9_881_900L + scenarioId).param("shippedAt", shippedAt)
                .param("createdAt", REQUESTED_AT.minusMinutes(5)).update();
    }

    private record LegacyCase(int id, String sourceStatus, String type, Integer approvedQuantity, String expectedSource) { }

    private record Allocation(String source, long shipmentItemId, int quantity, LocalDateTime createdAt) { }
}
