package org.muybaby.shopserver.support;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class InventoryAmountConstraintTestSupport {

    private InventoryAmountConstraintTestSupport() {
    }

    static void insertValidLegacyRows(JdbcClient jdbc) {
        jdbc.sql("""
                        insert into product_sku
                            (id, spu_id, sku_code, spec_json, spec_text, price_cent, status,
                             combination_key, deleted_at)
                        values (9890201, 9890021, 'V20-PLACEHOLDER', '{}', '', 0, 'DISABLED',
                                'V20-PLACEHOLDER', current_timestamp)
                        """).update();
        jdbc.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key, checkout_request_digest)
                        values (9890202, 'V20-CONSTRAINTS', 9890201, 'CLOSED', 'v20-constraints',
                                'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff')
                        """).update();
        jdbc.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, sku_code, quantity)
                        values (9890203, 9890202, 9890201, 9890021, 'V20 placeholder',
                                'V20-PLACEHOLDER', 1)
                        """).update();
        jdbc.sql("""
                        insert into stock_lock (id, order_id, order_item_id, sku_id, quantity, status)
                        values (9890204, 9890202, 9890203, 9890201, 1, 'RELEASED')
                        """).update();
        jdbc.sql("""
                        insert into stock_log
                            (id, sku_id, change_type, quantity_before, quantity_delta, quantity_after,
                             reason, operator_type, operator_id)
                        values (9890205, 9890201, 'MANUAL_ADJUST', 1, -1, 0,
                                'V20 constraint verification', 'ADMIN', 1)
                        """).update();
    }

    static void assertLegacyValuesAndWriteConstraints(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                        select count(*) from product_sku
                        where id = 9890201 and price_cent = 0 and original_price_cent = 0
                          and stock_available = 0 and cost_price_cent is null and deleted_at is not null
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        select count(*) from order_item
                        where id = 9890203 and unit_cost_cent is null and line_cost_cent is null
                          and coupon_discount_allocated_cent is null and freight_allocated_cent is null
                          and paid_amount_allocated_cent is null
                        """).query(Long.class).single()).isEqualTo(1L);

        assertNegativeUpdatesRejected(jdbc, "product_sku", 9890201,
                List.of("stock_available", "low_stock_threshold"));
        assertNegativeUpdatesRejected(jdbc, "product_sku", 9890201,
                List.of("price_cent", "original_price_cent", "cost_price_cent"));
        assertNegativeUpdatesRejected(jdbc, "order_item", 9890203,
                List.of("original_price_cent", "unit_price_cent", "retail_unit_price_cent",
                        "line_original_amount_cent", "line_amount_cent", "unit_cost_cent", "line_cost_cent",
                        "coupon_discount_allocated_cent", "freight_allocated_cent", "paid_amount_allocated_cent"));
        assertNegativeUpdatesRejected(jdbc, "shop_order", 9890202,
                List.of("product_original_amount_cent", "product_amount_cent", "coupon_discount_cent",
                        "freight_cent", "payable_amount_cent", "paid_amount_cent"));
        for (String table : List.of("stock_lock", "order_item")) {
            long id = table.equals("stock_lock") ? 9890204 : 9890203;
            assertThatThrownBy(() -> jdbc.sql("update " + table + " set quantity = 0 where id = :id")
                    .param("id", id).update())
                    .as("%s rejects zero quantities", table)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("chk_" + table + "_quantity_positive");
        }
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into stock_lock (order_id, order_item_id, sku_id, quantity, status)
                        values (9890202, 9890203, 9890201, 1, 'LOCKED')
                        """).update())
                .as("Released stock locks still occupy their order item identity")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_stock_lock_order_item");
        assertThatThrownBy(() -> jdbc.sql("""
                        update stock_log set quantity_after = 1 where id = 9890205
                        """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_stock_log_quantity_balance");
        assertThatThrownBy(() -> jdbc.sql("""
                        update stock_log set quantity_before = -1, quantity_delta = 1, quantity_after = 0
                        where id = 9890205
                        """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_stock_log_inventory_nonnegative");
        assertThatThrownBy(() -> jdbc.sql("""
                        update stock_log set quantity_before = 0, quantity_delta = -1, quantity_after = -1
                        where id = 9890205
                        """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_stock_log_inventory_nonnegative");
        assertThatCode(() -> jdbc.sql("""
                        update stock_log
                        set quantity_before = 2147483647, quantity_delta = -2147483647, quantity_after = 0
                        where id = 9890205
                        """).update()).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.sql("""
                        update stock_log
                        set quantity_before = 0, quantity_delta = 2147483647, quantity_after = 2147483647
                        where id = 9890205
                        """).update()).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.sql("""
                        update stock_log
                        set quantity_before = 2147483647, quantity_delta = 1, quantity_after = 0
                        where id = 9890205
                        """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_stock_log_quantity_balance");
    }

    private static void assertNegativeUpdatesRejected(
            JdbcClient jdbc, String table, long id, List<String> columns
    ) {
        for (String column : columns) {
            assertThatThrownBy(() -> jdbc.sql("update " + table + " set " + column + " = -1 where id = :id")
                    .param("id", id).update())
                    .as("%s.%s rejects negative values", table, column)
                    .isInstanceOf(DataAccessException.class);
        }
    }
}
