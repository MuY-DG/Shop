package org.muybaby.shopserver.product.engagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductEngagementSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void reviewFavoriteAndBrowseHistoryTablesExistWithExpectedUniqueness() {
        long userId = -94801L;
        long categoryId = -94802L;
        long spuId = -94803L;
        long skuId = -94804L;
        long orderId = -94805L;
        long orderItemId = -94806L;

        jdbcClient.sql("""
                        INSERT INTO app_user (id, openid, nickname, phone_authorized, status)
                        VALUES (:id, 'engagement-schema-user', '结构测试用户', FALSE, 'ENABLED')
                        """)
                .param("id", userId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_category (id, parent_id, name, icon, sort_order, status)
                        VALUES (:id, 0, '互动结构分类', '', 1, 'ENABLED')
                        """)
                .param("id", categoryId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_spu (
                            id, category_id, title, selling_points, detail_html, status
                        ) VALUES (:id, :categoryId, '互动结构商品', '', '', 'ON_SALE')
                        """)
                .param("id", spuId)
                .param("categoryId", categoryId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_sku (
                            id, spu_id, sku_code, spec_json, spec_text, price_cent,
                            stock_available, image, status
                        ) VALUES (:id, :spuId, 'ENGAGEMENT-SCHEMA-SKU', '{}', '默认', 100,
                                  1, '', 'ENABLED')
                        """)
                .param("id", skuId)
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO shop_order (
                            id, order_no, user_id, status, idempotency_key, completed_at
                        ) VALUES (:id, 'ENGAGEMENT-SCHEMA-ORDER', :userId, 'COMPLETED',
                                  'engagement-schema-key', CURRENT_TIMESTAMP)
                        """)
                .param("id", orderId)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_item (
                            id, order_id, sku_id, spu_id, product_title, sku_code, spec_text, quantity
                        ) VALUES (:id, :orderId, :skuId, :spuId, '互动结构商品',
                                  'ENGAGEMENT-SCHEMA-SKU', '默认', 1)
                        """)
                .param("id", orderItemId)
                .param("orderId", orderId)
                .param("skuId", skuId)
                .param("spuId", spuId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO product_review (
                            user_id, spu_id, source_order_item_id, order_item_id,
                            product_title_snapshot, spec_text_snapshot, verified_purchase,
                            rating, content, anonymous
                        ) VALUES (:userId, :spuId, :orderItemId, :orderItemId,
                                  '互动结构商品', '默认', TRUE, 5, '很好', FALSE)
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .param("orderItemId", orderItemId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO user_product_favorite (user_id, spu_id)
                        VALUES (:userId, :spuId)
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO user_product_browse_history (
                            user_id, spu_id, first_viewed_at, last_viewed_at, view_count
                        ) VALUES (:userId, :spuId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2)
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .update();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM product_review WHERE spu_id = :spuId")
                .param("spuId", spuId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_product_favorite WHERE user_id = :userId")
                .param("userId", userId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT view_count FROM user_product_browse_history WHERE user_id = :userId")
                .param("userId", userId).query(Long.class).single()).isEqualTo(2);
    }
}
