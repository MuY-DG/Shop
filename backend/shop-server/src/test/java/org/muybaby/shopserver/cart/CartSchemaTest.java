package org.muybaby.shopserver.cart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CartSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void cartItemTableAcceptsOneSkuPerUserAndKeepsQuantity() {
        jdbcClient.sql("""
                        insert into app_user (id, openid, phone_authorized, status)
                        values (9911, 'cart-schema-openid', false, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (9912, 0, 'Cart Schema Category', '', 1, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu (id, category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (9913, 9912, 'Cart Schema SPU', 'subtitle', 'https://example.test/main.jpg', 'A,B', '<p>detail</p>', 1, 'ON_SALE')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_sku (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent, stock_available, weight_gram, image, status, sort_order)
                        values (9914, 9913, 'CART-SCHEMA-SKU', '{\"规格\":\"300g\"}', '300g', 3990, 4990, 10, 300, 'https://example.test/sku.jpg', 'ENABLED', 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into cart_item (id, user_id, sku_id, quantity)
                        values (9915, 9911, 9914, 2)
                        """)
                .update();

        Integer quantity = jdbcClient.sql("select quantity from cart_item where user_id = 9911 and sku_id = 9914")
                .query(Integer.class)
                .single();

        assertThat(quantity).isEqualTo(2);
    }
}
