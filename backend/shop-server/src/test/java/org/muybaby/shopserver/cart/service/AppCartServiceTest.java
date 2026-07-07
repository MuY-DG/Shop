package org.muybaby.shopserver.cart.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.cart.dto.AddCartItemRequest;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppCartServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Test
    void addMergesExistingRowAfterDuplicateKeyRetry() {
        long userId = insertAppUser("cart-race-openid");
        long skuId = insertSellableSku("CART-RACE-SKU", 3990L, 4990L, 20);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(TokenKind.APP, userId, "app-user", List.of(), List.of());

        AppCartService appCartService = new AppCartService(
                jdbcClient,
                duplicateInsertNamedParameterJdbcTemplate(userId, skuId, 4)
        );

        CartItemResponse response = appCartService.add(principal, new AddCartItemRequest(skuId, 2));

        assertThat(response.quantity()).isEqualTo(6);
        Integer quantity = jdbcClient.sql("""
                        select quantity
                        from cart_item
                        where user_id = :userId and sku_id = :skuId
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        assertThat(quantity).isEqualTo(6);
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from cart_item
                        where user_id = :userId and sku_id = :skuId
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);
    }

    private NamedParameterJdbcTemplate duplicateInsertNamedParameterJdbcTemplate(Long userId, Long skuId, int existingQuantity) {
        AtomicBoolean duplicateCreated = new AtomicBoolean(false);
        return new NamedParameterJdbcTemplate(namedParameterJdbcTemplate.getJdbcTemplate()) {
            @Override
            public int update(String sql, org.springframework.jdbc.core.namedparam.SqlParameterSource paramSource, KeyHolder generatedKeyHolder, String[] keyColumnNames) {
                if (sql.contains("INSERT INTO cart_item") && duplicateCreated.compareAndSet(false, true)) {
                    insertCartItem(userId, skuId, existingQuantity);
                    throw new DuplicateKeyException("simulated cart item duplicate");
                }
                return super.update(sql, paramSource, generatedKeyHolder, keyColumnNames);
            }
        };
    }

    private long insertAppUser(String openid) {
        long userId = Math.abs(openid.hashCode()) + 10000L;
        jdbcClient.sql("""
                        insert into app_user (id, openid, unionid, status, last_login_at)
                        values (:id, :openid, :unionid, 'ENABLED', :lastLoginAt)
                        """)
                .param("id", userId)
                .param("openid", openid)
                .param("unionid", openid + "-unionid")
                .param("lastLoginAt", LocalDateTime.now())
                .update();
        return userId;
    }

    private long insertSellableSku(String skuCode, long priceCent, long originalPriceCent, int stockAvailable) {
        long categoryId = Math.abs((skuCode + "-cat").hashCode()) + 20000L;
        long spuId = Math.abs((skuCode + "-spu").hashCode()) + 30000L;
        long skuId = Math.abs((skuCode + "-sku").hashCode()) + 40000L;
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (:id, 0, :name, '', 1, 'ENABLED')
                        """)
                .param("id", categoryId)
                .param("name", "Cart Category " + skuCode)
                .update();
        jdbcClient.sql("""
                        insert into product_spu (id, category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (:id, :categoryId, :title, 'Cart subtitle', 'https://example.test/main.jpg',
                                '牛油浓香,手工炒制', '<p>detail</p>', 1, 'ON_SALE')
                        """)
                .param("id", spuId)
                .param("categoryId", categoryId)
                .param("title", "Cart SPU " + skuCode)
                .update();
        jdbcClient.sql("""
                        insert into product_sku (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                                                 stock_available, weight_gram, image, status, sort_order)
                        values (:id, :spuId, :skuCode, '{"规格":"300g"}', '300g', :priceCent, :originalPriceCent,
                                :stockAvailable, 300, 'https://example.test/sku.jpg', 'ENABLED', 1)
                        """)
                .param("id", skuId)
                .param("spuId", spuId)
                .param("skuCode", skuCode)
                .param("priceCent", priceCent)
                .param("originalPriceCent", originalPriceCent)
                .param("stockAvailable", stockAvailable)
                .update();
        return skuId;
    }

    private void insertCartItem(Long userId, Long skuId, int quantity) {
        jdbcClient.sql("""
                        insert into cart_item (user_id, sku_id, quantity)
                        values (:userId, :skuId, :quantity)
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .param("quantity", quantity)
                .update();
    }
}
