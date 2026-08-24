package org.muybaby.shopserver.cart.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.cart.dto.AddCartItemRequest;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(AppCartServiceTest.DuplicateInsertConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppCartServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AppCartService appCartService;

    @Autowired
    private DuplicateInsertProbe duplicateInsertProbe;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void addMergesExistingRowAfterDuplicateKeyRetryThroughTransactionalProxy() {
        long userId = insertAppUser("cart-race-openid");
        long skuId = insertSellableSku("CART-RACE-SKU", 3990L, 4990L, 20);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(TokenKind.APP, userId, "app-user", List.of(), List.of());

        assertThat(AopUtils.isAopProxy(appCartService)).isTrue();
        duplicateInsertProbe.arm(userId, skuId, 4);

        CartItemResponse response = appCartService.add(principal, new AddCartItemRequest(skuId, 2));

        assertThat(duplicateInsertProbe.wasTriggered()).isTrue();
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

    @Test
    void cartAddAnalyticsIsPublishedOnlyAfterTheCartTransactionCommits() {
        long userId = insertAppUser("cart-analytics-openid");
        long skuId = insertSellableSku("CART-ANALYTICS-SKU", 3990L, 4990L, 20);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.APP, userId, "app-user", List.of(), List.of());
        AddCartItemRequest request = new AddCartItemRequest(
                skuId,
                2,
                "00000000-0000-4000-8000-000000000081",
                "00000000-0000-4000-8000-000000000082",
                "1001");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            appCartService.add(principal, request);
            throw new ForcedRollbackException();
        })).isInstanceOf(ForcedRollbackException.class);

        assertThat(jdbcClient.sql("select count(*) from cart_item where user_id = :userId and sku_id = :skuId")
                .param("userId", userId)
                .param("skuId", skuId)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from analytics_event where visitor_id = :visitorId")
                .param("visitorId", request.analyticsVisitorId())
                .query(Integer.class)
                .single()).isZero();

        appCartService.add(principal, request);

        assertThat(jdbcClient.sql("select count(*) from analytics_event where visitor_id = :visitorId and event_type = 'CART_ADD'")
                .param("visitorId", request.analyticsVisitorId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void cartReturnsAppliedAndNextWholesaleTierPricing() {
        long userId = insertAppUser("cart-wholesale-openid");
        long skuId = insertSellableSku("CART-WHOLESALE-SKU", 1_000L, 1_200L, 100);
        jdbcClient.sql("""
                        insert into product_sku_wholesale_tier (sku_id, min_quantity, unit_price_cent)
                        values (:skuId, 10, 880), (:skuId, 50, 760)
                        """)
                .param("skuId", skuId)
                .update();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.APP, userId, "app-user", List.of(), List.of());

        CartItemResponse response = appCartService.add(principal, new AddCartItemRequest(skuId, 10));

        assertThat(response.priceCent()).isEqualTo(880L);
        assertThat(response.retailPriceCent()).isEqualTo(1_000L);
        assertThat(response.wholesaleTierMinQuantity()).isEqualTo(10);
        assertThat(response.nextWholesaleTierMinQuantity()).isEqualTo(50);
        assertThat(response.nextWholesaleTierPriceCent()).isEqualTo(760L);
        assertThat(response.nextWholesaleTierQuantityNeeded()).isEqualTo(40);
        assertThat(response.lineAmountCent()).isEqualTo(8_800L);
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
                                                 stock_available, weight_gram, image, status, sort_order, combination_key)
                        values (:id, :spuId, :skuCode, '{"规格":"300g"}', '300g', :priceCent, :originalPriceCent,
                                :stockAvailable, 300, 'https://example.test/sku.jpg', 'ENABLED', 1, :skuCode)
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

    @TestConfiguration(proxyBeanMethods = false)
    static class DuplicateInsertConfiguration {

        @Bean
        DuplicateInsertProbe duplicateInsertProbe() {
            return new DuplicateInsertProbe();
        }

        @Bean
        @Primary
        NamedParameterJdbcTemplate duplicateInsertNamedParameterJdbcTemplate(
                DataSource dataSource,
                DuplicateInsertProbe duplicateInsertProbe
        ) {
            return new DuplicateInsertNamedParameterJdbcTemplate(dataSource, duplicateInsertProbe);
        }
    }

    static class DuplicateInsertProbe {

        private final AtomicBoolean triggered = new AtomicBoolean(false);
        private Long userId;
        private Long skuId;
        private Integer existingQuantity;

        void arm(Long userId, Long skuId, Integer existingQuantity) {
            this.userId = userId;
            this.skuId = skuId;
            this.existingQuantity = existingQuantity;
            this.triggered.set(false);
        }

        boolean shouldCreateDuplicate(String sql) {
            return userId != null
                    && skuId != null
                    && existingQuantity != null
                    && sql.contains("INSERT INTO cart_item")
                    && triggered.compareAndSet(false, true);
        }

        boolean wasTriggered() {
            return triggered.get();
        }
    }

    static class DuplicateInsertNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {

        private final JdbcTemplate jdbcTemplate;
        private final DuplicateInsertProbe duplicateInsertProbe;

        DuplicateInsertNamedParameterJdbcTemplate(
                DataSource dataSource,
                DuplicateInsertProbe duplicateInsertProbe
        ) {
            this(new JdbcTemplate(dataSource), duplicateInsertProbe);
        }

        private DuplicateInsertNamedParameterJdbcTemplate(
                JdbcTemplate jdbcTemplate,
                DuplicateInsertProbe duplicateInsertProbe
        ) {
            super(jdbcTemplate);
            this.jdbcTemplate = jdbcTemplate;
            this.duplicateInsertProbe = duplicateInsertProbe;
        }

        @Override
        public int update(
                String sql,
                SqlParameterSource paramSource,
                KeyHolder generatedKeyHolder,
                String[] keyColumnNames
        ) {
            if (duplicateInsertProbe.shouldCreateDuplicate(sql)) {
                jdbcTemplate.update(
                        "insert into cart_item (user_id, sku_id, quantity) values (?, ?, ?)",
                        duplicateInsertProbe.userId,
                        duplicateInsertProbe.skuId,
                        duplicateInsertProbe.existingQuantity
                );
            }
            return super.update(sql, paramSource, generatedKeyHolder, keyColumnNames);
        }
    }

    private static final class ForcedRollbackException extends RuntimeException {
    }
}
