package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.CheckoutSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutSelectionServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(82_000L);

    @Autowired
    private CheckoutSelectionService checkoutSelectionService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearCheckoutState() {
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from cart_item").update();
        jdbcClient.sql("delete from product_sku").update();
        jdbcClient.sql("delete from product_spu_image").update();
        jdbcClient.sql("delete from product_spu").update();
        jdbcClient.sql("delete from product_category").update();
        jdbcClient.sql("delete from freight_template where id <> 1").update();
        jdbcClient.sql("delete from user_address").update();
    }

    @Test
    void conditionallyRejectsMixedSourcesAndDirectQuantityBounds() {
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> checkoutSelectionService.validate(new CheckoutRequest(
                        CheckoutSource.CART, List.of(1L), 9L, null, null, null)));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> checkoutSelectionService.validate(new CheckoutRequest(
                        CheckoutSource.CART, List.of(1L), null, 2, null, null)));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> checkoutSelectionService.validate(new CheckoutRequest(
                        CheckoutSource.DIRECT, List.of(1L), 9L, 2, null, null)));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> checkoutSelectionService.validate(new CheckoutRequest(
                        CheckoutSource.DIRECT, List.of(), 9L, 0, null, null)));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> checkoutSelectionService.validate(new CheckoutRequest(
                        CheckoutSource.DIRECT, List.of(), 9L, 1000, null, null)));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> checkoutSelectionService.validate(new CheckoutRequest(
                        CheckoutSource.CART, List.of(), null, null, null, null)));
    }

    @Test
    void cartAndDirectUseTheSameSnapshotPricingBuilderWithoutMutatingCart() {
        long userId = insertUser("selection-shared");
        long skuId = insertSku("SELECTION-SHARED", 3990L, 4990L, 8, "ENABLED", "ON_SALE", "ENABLED");
        long cartItemId = insertCartItem(userId, skuId, 2);
        Map<Long, Integer> before = cartQuantities(userId);

        CheckoutSelection cart = checkoutSelectionService.preview(userId,
                new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null));
        CheckoutSelection direct = checkoutSelectionService.preview(userId,
                new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 2, null, null));

        assertThat(cart.source()).isEqualTo(CheckoutSource.CART);
        assertThat(cart.selectedCartItemIds()).containsExactly(cartItemId);
        assertThat(direct.source()).isEqualTo(CheckoutSource.DIRECT);
        assertThat(direct.selectedCartItemIds()).isEmpty();
        assertThat(direct.previewItems()).hasSize(1);
        assertThat(direct.previewItems().getFirst().cartItemId()).isNull();
        assertThat(direct.previewItems().getFirst())
                .usingRecursiveComparison()
                .ignoringFields("cartItemId")
                .isEqualTo(cart.previewItems().getFirst());
        assertThat(direct.productOriginalAmountCent()).isEqualTo(cart.productOriginalAmountCent());
        assertThat(direct.productAmountCent()).isEqualTo(cart.productAmountCent());
        assertThat(cartQuantities(userId)).isEqualTo(before);
    }

    @Test
    void cartAndDirectApplyTheHighestReachedWholesaleTier() {
        long userId = insertUser("selection-wholesale");
        long skuId = insertSku("SELECTION-WHOLESALE", 1_000L, 1_200L, 100,
                "ENABLED", "ON_SALE", "ENABLED");
        jdbcClient.sql("""
                        insert into product_sku_wholesale_tier (sku_id, min_quantity, unit_price_cent)
                        values (:skuId, 10, 880), (:skuId, 50, 760)
                        """)
                .param("skuId", skuId)
                .update();

        CheckoutSelection belowTier = checkoutSelectionService.preview(userId,
                new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 9, null, null));
        CheckoutSelection firstTier = checkoutSelectionService.preview(userId,
                new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 10, null, null));
        long cartItemId = insertCartItem(userId, skuId, 50);
        CheckoutSelection highestCartTier = checkoutSelectionService.preview(userId,
                new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null));

        assertThat(belowTier.previewItems().getFirst().unitPriceCent()).isEqualTo(1_000L);
        assertThat(belowTier.previewItems().getFirst().wholesaleTierMinQuantity()).isNull();
        assertThat(firstTier.previewItems().getFirst().unitPriceCent()).isEqualTo(880L);
        assertThat(firstTier.previewItems().getFirst().retailUnitPriceCent()).isEqualTo(1_000L);
        assertThat(firstTier.previewItems().getFirst().wholesaleTierMinQuantity()).isEqualTo(10);
        assertThat(highestCartTier.previewItems().getFirst().unitPriceCent()).isEqualTo(760L);
        assertThat(highestCartTier.previewItems().getFirst().wholesaleTierMinQuantity()).isEqualTo(50);
        assertThat(highestCartTier.productAmountCent()).isEqualTo(38_000L);
        assertThat(checkoutSelectionService.lockForSubmit(userId,
                new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null)))
                .usingRecursiveComparison()
                .isEqualTo(highestCartTier);
    }

    @Test
    void directAndCartReportIdenticalDisabledAndSoldOutErrors() {
        long userId = insertUser("selection-errors");
        long skuId = insertSku("SELECTION-ERRORS", 3990L, 4990L, 5, "ENABLED", "ON_SALE", "ENABLED");
        long cartItemId = insertCartItem(userId, skuId, 2);
        CheckoutRequest cart = new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null);
        CheckoutRequest direct = new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 2, null, null);

        jdbcClient.sql("update product_sku set status = 'DISABLED' where id = :skuId")
                .param("skuId", skuId).update();
        assertBusiness(ErrorCode.SKU_UNAVAILABLE, () -> checkoutSelectionService.preview(userId, cart));
        assertBusiness(ErrorCode.SKU_UNAVAILABLE, () -> checkoutSelectionService.preview(userId, direct));

        jdbcClient.sql("update product_sku set status = 'ENABLED', stock_available = 1 where id = :skuId")
                .param("skuId", skuId).update();
        assertBusiness(ErrorCode.STOCK_SHORTAGE, () -> checkoutSelectionService.preview(userId, cart));
        assertBusiness(ErrorCode.STOCK_SHORTAGE, () -> checkoutSelectionService.preview(userId, direct));
    }

    @Test
    void previewAndSubmitRejectSoftDeletedSkuEvenWhenItsStatusRemainsEnabled() {
        long userId = insertUser("selection-deleted-sku");
        long skuId = insertSku("SELECTION-DELETED-SKU", 3990L, 4990L, 5, "ENABLED", "ON_SALE", "ENABLED");
        long cartItemId = insertCartItem(userId, skuId, 1);
        CheckoutRequest cart = new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null);
        CheckoutRequest direct = new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 1, null, null);

        jdbcClient.sql("""
                        update product_sku
                        set status = 'ENABLED', deleted_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", skuId)
                .update();

        assertBusiness(ErrorCode.CART_ITEM_NOT_FOUND, () -> checkoutSelectionService.preview(userId, cart));
        assertBusiness(ErrorCode.SKU_UNAVAILABLE, () -> checkoutSelectionService.preview(userId, direct));
        assertBusiness(ErrorCode.SKU_UNAVAILABLE, () -> checkoutSelectionService.lockForSubmit(userId, cart));
        assertBusiness(ErrorCode.SKU_UNAVAILABLE, () -> checkoutSelectionService.lockForSubmit(userId, direct));
    }

    @Test
    void previewAndSubmitRejectSoftDeletedSpuEvenWhenItsStatusRemainsOnSale() {
        long userId = insertUser("selection-deleted-spu");
        long skuId = insertSku("SELECTION-DELETED-SPU", 3990L, 4990L, 5, "ENABLED", "ON_SALE", "ENABLED");
        long cartItemId = insertCartItem(userId, skuId, 1);
        CheckoutRequest cart = new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null);
        CheckoutRequest direct = new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 1, null, null);
        long spuId = jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        update product_spu
                        set status = 'ON_SALE', deleted_at = current_timestamp
                        where id = :spuId
                        """)
                .param("spuId", spuId)
                .update();

        assertBusiness(ErrorCode.CART_ITEM_NOT_FOUND, () -> checkoutSelectionService.preview(userId, cart));
        assertBusiness(ErrorCode.SKU_UNAVAILABLE, () -> checkoutSelectionService.preview(userId, direct));
        assertBusiness(ErrorCode.PRODUCT_UNAVAILABLE, () -> checkoutSelectionService.lockForSubmit(userId, cart));
        assertBusiness(ErrorCode.PRODUCT_UNAVAILABLE, () -> checkoutSelectionService.lockForSubmit(userId, direct));
    }

    @Test
    void cartSelectionRequiresEveryRequestedRowToBelongToTheUser() {
        long ownerId = insertUser("selection-owner");
        long otherId = insertUser("selection-other");
        long skuId = insertSku("SELECTION-OWNERSHIP", 3990L, 4990L, 5, "ENABLED", "ON_SALE", "ENABLED");
        long cartItemId = insertCartItem(ownerId, skuId, 1);

        assertBusiness(ErrorCode.CART_ITEM_NOT_FOUND, () -> checkoutSelectionService.lockForSubmit(otherId,
                new CheckoutRequest(CheckoutSource.CART, List.of(cartItemId), null, null, null, null)));
    }

    @Test
    void directAndMultiProductCartResolveDistinctTemplateMaximumFreight() {
        long userId = insertUser("selection-freight");
        long lowerTemplateId = insertFreightTemplate("低固定运费", "FIXED", 700L, "ENABLED");
        long higherTemplateId = insertFreightTemplate("高固定运费", "FIXED", 1_200L, "ENABLED");
        long lowerSkuA = insertSku("FREIGHT-LOW-A", 2_000L, 2_200L, 10, "ENABLED", "ON_SALE", "ENABLED");
        long lowerSkuB = insertSku("FREIGHT-LOW-B", 3_000L, 3_200L, 10, "ENABLED", "ON_SALE", "ENABLED");
        long higherSku = insertSku("FREIGHT-HIGH", 4_000L, 4_200L, 10, "ENABLED", "ON_SALE", "ENABLED");
        bindFreightTemplate(lowerSkuA, lowerTemplateId);
        bindFreightTemplate(lowerSkuB, lowerTemplateId);
        bindFreightTemplate(higherSku, higherTemplateId);

        CheckoutSelection direct = checkoutSelectionService.preview(userId,
                new CheckoutRequest(CheckoutSource.DIRECT, List.of(), lowerSkuA, 1, null, null));
        long lowerCartA = insertCartItem(userId, lowerSkuA, 1);
        long lowerCartB = insertCartItem(userId, lowerSkuB, 1);
        long higherCart = insertCartItem(userId, higherSku, 1);
        CheckoutSelection cart = checkoutSelectionService.preview(userId,
                new CheckoutRequest(
                        CheckoutSource.CART,
                        List.of(lowerCartA, lowerCartB, higherCart),
                        null,
                        null,
                        null,
                        null
                ));

        assertThat(direct.freightCent()).isEqualTo(700L);
        assertThat(cart.freightCent()).isEqualTo(1_200L);
        assertThat(checkoutSelectionService.lockForSubmit(userId,
                new CheckoutRequest(
                        CheckoutSource.CART,
                        List.of(lowerCartA, lowerCartB, higherCart),
                        null,
                        null,
                        null,
                        null
                )).freightCent()).isEqualTo(cart.freightCent());
    }

    @Test
    void checkoutRejectsDisabledOrDeletedFreightTemplate() {
        long userId = insertUser("selection-freight-unavailable");
        long templateId = insertFreightTemplate("不可用运费", "FIXED", 600L, "DISABLED");
        long skuId = insertSku(
                "FREIGHT-UNAVAILABLE",
                2_000L,
                2_200L,
                10,
                "ENABLED",
                "ON_SALE",
                "ENABLED"
        );
        bindFreightTemplate(skuId, templateId);
        CheckoutRequest direct = new CheckoutRequest(CheckoutSource.DIRECT, List.of(), skuId, 1, null, null);

        assertBusiness(ErrorCode.PRODUCT_UNAVAILABLE, () -> checkoutSelectionService.preview(userId, direct));
        jdbcClient.sql("""
                        update freight_template
                        set status = 'ENABLED', deleted_at = current_timestamp
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .update();
        assertBusiness(ErrorCode.PRODUCT_UNAVAILABLE, () -> checkoutSelectionService.lockForSubmit(userId, direct));
    }

    private void assertBusiness(ErrorCode expected, Runnable action) {
        BusinessException exception = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(expected);
    }

    private long insertUser(String suffix) {
        long id = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values (:id, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("id", id)
                .param("openid", suffix + "-" + id)
                .param("unionid", suffix + "-union-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private long insertSku(
            String suffix,
            long priceCent,
            long originalPriceCent,
            int stock,
            String skuStatus,
            String spuStatus,
            String categoryStatus
    ) {
        long sequence = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 1, :status)
                        """)
                .param("name", "Category " + suffix + sequence)
                .param("status", categoryStatus)
                .update();
        long categoryId = jdbcClient.sql("select max(id) from product_category").query(Long.class).single();
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (:categoryId, :title, 'subtitle', :mainImage, '', '', 1, :status)
                        """)
                .param("categoryId", categoryId)
                .param("title", "Product " + suffix)
                .param("mainImage", "https://example.test/" + suffix + "/main.jpg")
                .param("status", spuStatus)
                .update();
        long spuId = jdbcClient.sql("select max(id) from product_spu").query(Long.class).single();
        String skuCode = suffix + "-" + sequence;
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order)
                        values (:spuId, :skuCode, '{}', '300g', :priceCent, :originalPriceCent,
                                :stock, 300, :image, :status, 1)
                        """)
                .param("spuId", spuId)
                .param("skuCode", skuCode)
                .param("priceCent", priceCent)
                .param("originalPriceCent", originalPriceCent)
                .param("stock", stock)
                .param("image", "https://example.test/" + suffix + "/sku.jpg")
                .param("status", skuStatus)
                .update();
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
    }

    private long insertCartItem(long userId, long skuId, int quantity) {
        jdbcClient.sql("""
                        insert into cart_item (user_id, sku_id, quantity)
                        values (:userId, :skuId, :quantity)
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .param("quantity", quantity)
                .update();
        return jdbcClient.sql("select id from cart_item where user_id = :userId and sku_id = :skuId")
                .param("userId", userId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();
    }

    private long insertFreightTemplate(String name, String chargeMode, long fixedAmountCent, String status) {
        jdbcClient.sql("""
                        insert into freight_template (name, charge_mode, fixed_amount_cent, status, sort_order)
                        values (:name, :chargeMode, :fixedAmountCent, :status, 0)
                        """)
                .param("name", name)
                .param("chargeMode", chargeMode)
                .param("fixedAmountCent", fixedAmountCent)
                .param("status", status)
                .update();
        return jdbcClient.sql("select max(id) from freight_template").query(Long.class).single();
    }

    private void bindFreightTemplate(long skuId, long templateId) {
        jdbcClient.sql("""
                        update product_spu
                        set freight_template_id = :templateId
                        where id = (select spu_id from product_sku where id = :skuId)
                        """)
                .param("templateId", templateId)
                .param("skuId", skuId)
                .update();
    }

    private Map<Long, Integer> cartQuantities(long userId) {
        return jdbcClient.sql("select id, quantity from cart_item where user_id = :userId order by id")
                .param("userId", userId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getInt("quantity")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
    }
}
