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
                        insert into storage_asset
                            (id, scope, media_kind, folder_id, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            (7001, 'LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS', '', 'public/schema/main.png', 'schema-main.png',
                             'image/png', 'png', 68, 'schema-main', 1, 1, '', null,
                             'http://localhost:8080/files/public/schema/main.png', 'ACTIVE', 'ADMIN', 1),
                            (7002, 'LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS', '', 'public/schema/sku.png', 'schema-sku.png',
                             'image/png', 'png', 68, 'schema-sku', 1, 1, '', null,
                             'http://localhost:8080/files/public/schema/sku.png', 'ACTIVE', 'ADMIN', 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent)
                        values
                            (8001, 'ORD-SCHEMA-001', 1, 'CREATED', 'CART', 'schema-order-001',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             9980, 7980, 500, 0, 7480, 0)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, main_image_file_id, sku_image_file_id,
                             display_image_file_id, sku_code, quantity,
                             original_price_cent, unit_price_cent, line_original_amount_cent, line_amount_cent)
                        values
                            (8101, 8001, 9904, 9902, 'Schema Item', 7001, 7002,
                             7002, 'SCHEMA-SKU', 2,
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
                        where id in (830, 501)
                          and path in ('/trade', 'orders')
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
        Long displayImageFileId = jdbcClient.sql("""
                        select display_image_file_id
                        from order_item
                        where id = 8101
                        """)
                .query(Long.class)
                .single();

        assertThat(orderMenuCount).isEqualTo(2);
        assertThat(permissionCount).isEqualTo(2);
        assertThat(displayImageFileId).isEqualTo(7002L);
    }

    @Test
    void createdOrdersHaveAClaimablePaymentDeadline() {
        Integer columnCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'shop_order'
                          and lower(column_name) in (
                            'payment_expires_at',
                            'created_timeout_claim_token',
                            'created_timeout_claimed_at',
                            'created_timeout_attempts'
                          )
                        """)
                .query(Integer.class)
                .single();
        Integer indexCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(table_name) = 'shop_order'
                          and lower(index_name) = 'idx_shop_order_created_timeout'
                        """)
                .query(Integer.class)
                .single();

        assertThat(columnCount).isEqualTo(4);
        assertThat(indexCount).isEqualTo(1);
    }
}
