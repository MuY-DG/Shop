package org.muybaby.shopserver.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OrderSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void orderTablesAndAdminMenuExist() {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent)
                        values
                            (8001, 'ORD-SCHEMA-001', 1, 'CREATED', 'CART', 'schema-order-001',
                             9980, 7980, 500, 0, 7480, 0)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, sku_code, quantity,
                             original_price_cent, unit_price_cent, line_original_amount_cent, line_amount_cent)
                        values
                            (8101, 8001, 9904, 9902, 'Schema Item', 'SCHEMA-SKU', 2,
                             4990, 3990, 9980, 7980)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into stock_lock
                            (id, order_id, order_item_id, sku_id, quantity, status)
                        values
                            (8201, 8001, 8101, 9904, 2, 'LOCKED')
                        """)
                .update();

        Integer orderMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id in (500, 501)
                          and path in ('/order', 'list')
                          and component in ('/index/index', '/order/list')
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in ('order:read', 'order:close')
                        """)
                .query(Integer.class)
                .single();

        assertThat(orderMenuCount).isEqualTo(2);
        assertThat(permissionCount).isEqualTo(2);
    }
}
