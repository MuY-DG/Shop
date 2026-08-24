package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductCatalogSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void productTablesAcceptCategorySpuSkuImageAndStockLogRows() {
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (9901, 0, 'Schema Category', '', 1, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu (id, category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (9902, 9901, 'Schema SPU', 'Schema subtitle', 'https://example.test/main.jpg', 'A,B', '<p>detail</p>', 1, 'DRAFT')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_image (id, spu_id, url, sort_order)
                        values (9903, 9902, 'https://example.test/gallery.jpg', 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_sku (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent, stock_available, weight_gram, image, status, sort_order, combination_key)
                        values (9904, 9902, 'SCHEMA-SKU', '{"口味":"牛油"}', '牛油', 3990, 4990, 10, 300, 'https://example.test/sku.jpg', 'ENABLED', 1, 'SCHEMA-SKU')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into stock_log (id, sku_id, change_type, quantity_before, quantity_delta, quantity_after, reason, operator_type, operator_id)
                        values (9905, 9904, 'INITIAL', 0, 10, 10, 'schema test', 'SYSTEM', 0)
                        """)
                .update();

        Integer skuCount = jdbcClient.sql("select count(*) from product_sku where spu_id = 9902")
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("select count(*) from admin_permission where auth_mark like 'product:%'")
                .query(Integer.class)
                .single();

        assertThat(skuCount).isEqualTo(1);
        assertThat(permissionCount).isGreaterThanOrEqualTo(6);
    }
}
