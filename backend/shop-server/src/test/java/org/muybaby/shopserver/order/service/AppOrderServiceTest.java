package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.OrderStatusGroup;
import org.muybaby.shopserver.order.dto.AppOrderDetailResponse;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewResponse;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppOrderServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(84_000L);

    @Autowired
    private AppOrderService appOrderService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearOrderState() {
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from cart_item").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from user_address").update();
        jdbcClient.sql("delete from product_sku").update();
        jdbcClient.sql("delete from product_spu_image").update();
        jdbcClient.sql("delete from product_spu").update();
        jdbcClient.sql("delete from product_category").update();
    }

    @ParameterizedTest
    @CsvSource({
            "UNPAID,CREATED;PAYING",
            "TO_SHIP,PAID",
            "TO_RECEIVE,SHIPPED",
            "COMPLETED,COMPLETED"
    })
    void statusGroupReturnsOnlyMappedStatuses(OrderStatusGroup group, String allowedCsv) {
        long userId = insertUser("status-group-" + group);
        long otherUserId = insertUser("status-group-other-" + group);
        LocalDateTime base = LocalDateTime.of(2026, 7, 10, 9, 0);
        List<String> statuses = List.of("CREATED", "PAYING", "PAID", "SHIPPED", "COMPLETED", "CLOSED", "REFUNDING");
        for (int index = 0; index < statuses.size(); index++) {
            insertReadOrder(userId, statuses.get(index), base.plusMinutes(index));
        }
        insertReadOrder(otherUserId, statuses.getFirst(), base.plusHours(1));

        Set<String> allowed = Set.of(allowedCsv.split(";"));
        PageResult<OrderSummaryResponse> page = appOrderService.list(
                appPrincipal(userId), 1L, 10L, null, group);

        assertThat(page.records()).isNotEmpty().allMatch(item -> allowed.contains(item.status()));
        assertThat(page.records()).extracting(OrderSummaryResponse::status).containsExactlyInAnyOrderElementsOf(allowed);
        assertThat(page.total()).isEqualTo(allowed.size());
    }

    @Test
    void pagingIsStableExactStatusRemainsCompatibleAndAmbiguousFiltersReject() {
        long userId = insertUser("order-page");
        LocalDateTime base = LocalDateTime.of(2026, 7, 10, 10, 0);
        long oldest = insertReadOrder(userId, "CREATED", base);
        long tiedLower = insertReadOrder(userId, "PAID", base.plusMinutes(1));
        long tiedHigher = insertReadOrder(userId, "SHIPPED", base.plusMinutes(1));
        long newest = insertReadOrder(userId, "COMPLETED", base.plusMinutes(2));

        PageResult<OrderSummaryResponse> first = appOrderService.list(
                appPrincipal(userId), 1L, 2L, null, OrderStatusGroup.ALL);
        PageResult<OrderSummaryResponse> second = appOrderService.list(
                appPrincipal(userId), 2L, 2L, null, OrderStatusGroup.ALL);
        PageResult<OrderSummaryResponse> exact = appOrderService.list(
                appPrincipal(userId), 1L, 10L, "PAID", OrderStatusGroup.ALL);
        PageResult<OrderSummaryResponse> clamped = appOrderService.list(
                appPrincipal(userId), 1L, 1_000L, null, OrderStatusGroup.ALL);

        assertThat(first.total()).isEqualTo(4L);
        assertThat(first.records()).extracting(OrderSummaryResponse::orderId).containsExactly(newest, tiedHigher);
        assertThat(second.records()).extracting(OrderSummaryResponse::orderId).containsExactly(tiedLower, oldest);
        assertThat(exact.records()).extracting(OrderSummaryResponse::orderId).containsExactly(tiedLower);
        assertThat(clamped.size()).isEqualTo(100L);
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appOrderService.list(appPrincipal(userId), 0L, 10L, null, OrderStatusGroup.ALL));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appOrderService.list(appPrincipal(userId), 1L, 0L, null, OrderStatusGroup.ALL));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appOrderService.list(appPrincipal(userId), 1L, 10L, "PAID", OrderStatusGroup.TO_SHIP));
    }

    @Test
    void detailReturnsLatestPersistedPaymentReceiverShipmentAfterSaleAndAllKeyTimes() {
        long userId = insertUser("detail-truth");
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 9, 8, 0);
        LocalDateTime paidAt = createdAt.plusMinutes(5);
        LocalDateTime shippedAt = createdAt.plusHours(2);
        LocalDateTime completedAt = createdAt.plusDays(1);
        LocalDateTime refundingAt = completedAt.plusHours(1);
        LocalDateTime refundedAt = completedAt.plusHours(2);
        long orderId = insertDetailedOrder(userId, "REFUNDED", createdAt, paidAt, shippedAt,
                completedAt, refundingAt, refundedAt);
        insertPayment(orderId, "PAY-OLD-" + orderId, "wx-old-" + orderId, "CLOSED",
                paidAt.minusMinutes(1), createdAt.plusMinutes(1));
        insertPayment(orderId, "PAY-LATEST-" + orderId, "wx-latest-" + orderId, "SUCCEEDED",
                paidAt, createdAt.plusMinutes(2));
        insertShipment(orderId, shippedAt);
        long olderAfterSale = insertAfterSale(orderId, userId, "REJECTED", completedAt.plusMinutes(1));
        long latestAfterSale = insertAfterSale(orderId, userId, "REFUNDED", completedAt.plusMinutes(2));

        AppOrderDetailResponse detail = appOrderService.detail(appPrincipal(userId), orderId);

        assertThat(detail.orderId()).isEqualTo(orderId);
        assertThat(detail.receiverName()).isEqualTo("Receiver Snapshot");
        assertThat(detail.receiverPhone()).isEqualTo("13800138000");
        assertThat(detail.receiverAddress()).isEqualTo("Persisted Receiver Address");
        assertThat(detail.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(detail.outTradeNo()).isEqualTo("PAY-LATEST-" + orderId);
        assertThat(detail.transactionId()).isEqualTo("wx-latest-" + orderId);
        assertThat(detail.paymentTransactionId()).isEqualTo("wx-latest-" + orderId);
        assertThat(detail.merchantTradeNo()).isEqualTo("legacy-trade-" + orderId);
        assertThat(detail.paidAt()).isEqualTo(paidAt);
        assertThat(detail.shippedAt()).isEqualTo(shippedAt);
        assertThat(detail.completedAt()).isEqualTo(completedAt);
        assertThat(detail.refundingAt()).isEqualTo(refundingAt);
        assertThat(detail.refundedAt()).isEqualTo(refundedAt);
        assertThat(detail.shipment()).isNotNull();
        assertThat(detail.shipment().trackingNo()).isEqualTo("TRACK-" + orderId);
        assertThat(detail.latestAfterSale()).isNotNull();
        assertThat(detail.latestAfterSale().id()).isEqualTo(latestAfterSale).isNotEqualTo(olderAfterSale);
    }

    @Test
    void detailWithoutPaymentShipmentOrAfterSaleKeepsNestedFieldsNullable() {
        long userId = insertUser("detail-nullable");
        long orderId = insertDetailedOrder(userId, "CREATED", LocalDateTime.of(2026, 7, 9, 9, 0),
                null, null, null, null, null);

        AppOrderDetailResponse detail = appOrderService.detail(appPrincipal(userId), orderId);

        assertThat(detail.paymentStatus()).isNull();
        assertThat(detail.paidAt()).isNull();
        assertThat(detail.shipment()).isNull();
        assertThat(detail.latestAfterSale()).isNull();
        assertThat(detail.completedAt()).isNull();
        assertThat(detail.refundingAt()).isNull();
        assertThat(detail.refundedAt()).isNull();
    }

    @Test
    void detailLatestPaymentUsesPersistedOrderFallbackForBlankTradeFieldsAndMissingPaidAt() {
        long userId = insertUser("detail-payment-fallback");
        LocalDateTime paidAt = LocalDateTime.of(2026, 7, 9, 10, 5);
        long orderId = insertDetailedOrder(userId, "PAYING", paidAt.minusMinutes(5),
                paidAt, null, null, null, null);
        insertPayment(orderId, "", "", "PAYING", null, paidAt.plusMinutes(1));

        AppOrderDetailResponse detail = appOrderService.detail(appPrincipal(userId), orderId);

        assertThat(detail.paymentStatus()).isEqualTo("PAYING");
        assertThat(detail.outTradeNo()).isEqualTo("legacy-trade-" + orderId);
        assertThat(detail.transactionId()).isEqualTo("legacy-tx-" + orderId);
        assertThat(detail.paymentTransactionId()).isEqualTo("legacy-tx-" + orderId);
        assertThat(detail.paidAt()).isEqualTo(paidAt);
    }

    @Test
    void confirmReceiptIsOwnedAtomicStateTransitionAndCompletedReplayIsIdempotent() {
        long userId = insertUser("receipt-owner");
        long otherUserId = insertUser("receipt-other");
        long shippedOrderId = insertReadOrder(userId, "SHIPPED", LocalDateTime.of(2026, 7, 10, 11, 0));
        long invalidOrderId = insertReadOrder(userId, "PAID", LocalDateTime.of(2026, 7, 10, 11, 1));

        OrderReceiptResponse first = appOrderService.confirmReceipt(appPrincipal(userId), shippedOrderId);
        OrderReceiptResponse replay = appOrderService.confirmReceipt(appPrincipal(userId), shippedOrderId);

        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.completedAt()).isNotNull();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcClient.sql("select completed_at from shop_order where id = :orderId")
                .param("orderId", shippedOrderId).query(LocalDateTime.class).single()).isEqualTo(first.completedAt());
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appOrderService.confirmReceipt(appPrincipal(otherUserId), shippedOrderId));
        assertBusiness(ErrorCode.ORDER_STATE_CONFLICT,
                () -> appOrderService.confirmReceipt(appPrincipal(userId), invalidOrderId));
    }

    @Test
    void directPreviewSubmitReplayAndRejectedSubmitNeverMutateExistingCartAndSnapshotReceiver() {
        long userId = insertUser("direct-user");
        long addressId = insertAddress(userId, "张三", "13800138000", "北京市", "", "朝阳区", "火锅路1号");
        long otherSkuId = insertSku("DIRECT-CART-OTHER", 1000L, 1200L, 20);
        long directSkuId = insertSku("DIRECT-CHECKOUT", 3990L, 4990L, 10);
        long existingCartId = insertCartItem(userId, otherSkuId, 3);
        long couponId = insertCoupon(userId, "Direct Five Off", 500L);
        Map<Long, Integer> before = cartQuantities(userId);

        OrderPreviewResponse preview = appOrderService.preview(appPrincipal(userId),
                new AppOrderPreviewRequest(CheckoutSource.DIRECT, List.of(), directSkuId, 2, addressId, couponId));
        assertThat(preview.items()).hasSize(1);
        assertThat(preview.items().getFirst().cartItemId()).isNull();
        assertThat(preview.items().getFirst().quantity()).isEqualTo(2);
        assertThat(preview.couponDiscountCent()).isEqualTo(500L);
        assertThat(cartQuantities(userId)).isEqualTo(before);

        AppOrderSubmitRequest request = new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), directSkuId, 2, addressId, couponId, "direct-001");
        OrderSubmitResponse result = appOrderService.submit(appPrincipal(userId), request);

        assertThat(orderSource(result.orderId())).isEqualTo("DIRECT");
        assertThat(cartQuantities(userId)).isEqualTo(before);
        assertThat(cartRowExists(existingCartId)).isTrue();
        assertReceiverSnapshot(result.orderId(), "张三", "13800138000", "北京市朝阳区火锅路1号");
        assertThat(count("stock_lock", result.orderId())).isEqualTo(1L);
        assertThat(jdbcClient.sql("select status from user_coupon where id = :couponId")
                .param("couponId", couponId).query(String.class).single()).isEqualTo("LOCKED");

        OrderSubmitResponse replay = appOrderService.submit(appPrincipal(userId), request);
        assertThat(replay.orderId()).isEqualTo(result.orderId());
        assertThat(cartQuantities(userId)).isEqualTo(before);
        assertThat(count("stock_lock", result.orderId())).isEqualTo(1L);

        assertBusiness(ErrorCode.ORDER_STATE_CONFLICT, () -> appOrderService.submit(appPrincipal(userId),
                new AppOrderSubmitRequest(
                        CheckoutSource.DIRECT, List.of(), directSkuId, 3, addressId, couponId, "direct-001")));
        assertThat(cartQuantities(userId)).isEqualTo(before);
    }

    @Test
    void cartSubmitDeletesOnlySelectedOwnedRowsAndMatchingDigestReplaysAfterDeletion() {
        long userId = insertUser("cart-selected-user");
        long addressId = insertAddress(userId, "李四", "13900139000", "上海市", "", "浦东新区", "辣锅路2号");
        long selectedSkuId = insertSku("CART-SELECTED", 2500L, 3000L, 10);
        long retainedSkuId = insertSku("CART-RETAINED", 1800L, 2000L, 10);
        long selectedCartId = insertCartItem(userId, selectedSkuId, 2);
        long retainedCartId = insertCartItem(userId, retainedSkuId, 4);

        AppOrderSubmitRequest request = new AppOrderSubmitRequest(
                null, List.of(selectedCartId, selectedCartId), null, null, addressId, null, "cart-selected-001");
        OrderSubmitResponse created = appOrderService.submit(appPrincipal(userId), request);

        assertThat(orderSource(created.orderId())).isEqualTo("CART");
        assertThat(cartRowExists(selectedCartId)).isFalse();
        assertThat(cartRowExists(retainedCartId)).isTrue();
        assertThat(cartQuantities(userId)).containsExactlyEntriesOf(Map.of(retainedCartId, 4));
        assertThat(jdbcClient.sql("select checkout_request_digest from shop_order where id = :orderId")
                .param("orderId", created.orderId()).query(String.class).single()).hasSize(64);

        OrderSubmitResponse replay = appOrderService.submit(appPrincipal(userId), request);
        assertThat(replay.orderId()).isEqualTo(created.orderId());
        assertThat(cartRowExists(retainedCartId)).isTrue();
        assertThat(count("stock_lock", created.orderId())).isEqualTo(1L);
    }

    @Test
    void sameKeyRejectsChangedSourceIdsQuantityAddressAndRequestedCouponBeforeCheckoutReads() {
        long userId = insertUser("digest-conflict-user");
        long addressA = insertAddress(userId, "王五", "13700137000", "广东省", "深圳市", "南山区", "锅底路3号");
        long addressB = insertAddress(userId, "赵六", "13600136000", "广东省", "深圳市", "福田区", "蘸料路4号");
        long skuA = insertSku("DIGEST-A", 2000L, 2200L, 20);
        long skuB = insertSku("DIGEST-B", 3000L, 3300L, 20);
        long cartA = insertCartItem(userId, skuA, 1);
        long cartB = insertCartItem(userId, skuB, 1);

        appOrderService.submit(appPrincipal(userId), new AppOrderSubmitRequest(
                CheckoutSource.CART, List.of(cartA), null, null, addressA, null, "digest-cart"));
        assertConflict(userId, new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), skuB, 1, addressA, null, "digest-cart"));
        assertConflict(userId, new AppOrderSubmitRequest(
                CheckoutSource.CART, List.of(cartB), null, null, addressA, null, "digest-cart"));

        appOrderService.submit(appPrincipal(userId), new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), skuA, 1, addressA, null, "digest-direct"));
        assertConflict(userId, new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), skuA, 2, addressA, null, "digest-direct"));
        assertConflict(userId, new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), skuA, 1, addressB, null, "digest-direct"));
        assertConflict(userId, new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), skuA, 1, addressA, 999_999L, "digest-direct"));
    }

    @Test
    void anotherUsersAddressRejectsAndRollsBackOwnershipStockAndOrderItems() {
        long userId = insertUser("address-owner-a");
        long otherUserId = insertUser("address-owner-b");
        long otherAddress = insertAddress(otherUserId, "其他人", "13500135000", "浙江省", "杭州市", "西湖区", "清汤路5号");
        long skuId = insertSku("ADDRESS-REJECT", 3990L, 4990L, 10);
        long cartId = insertCartItem(userId, skuId, 1);

        assertBusiness(ErrorCode.VALIDATION_FAILED, () -> appOrderService.submit(appPrincipal(userId),
                new AppOrderSubmitRequest(
                        CheckoutSource.CART, List.of(cartId), null, null, otherAddress, null, "address-reject")));

        assertThat(jdbcClient.sql("select count(*) from shop_order where idempotency_key = 'address-reject'")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId).query(Integer.class).single()).isEqualTo(10);
        assertThat(cartRowExists(cartId)).isTrue();
    }

    @Test
    void legacyBlankDigestRowsRetainReplayCompatibilityWithoutReadingCheckoutState() {
        long userId = insertUser("legacy-digest-user");
        insertLegacyOrder(userId, "legacy-key");

        OrderSubmitResponse replay = appOrderService.submit(appPrincipal(userId), new AppOrderSubmitRequest(
                CheckoutSource.DIRECT, List.of(), 999_999L, 1, 999_999L, 888_888L, "legacy-key"));

        assertThat(replay.orderNo()).isEqualTo("ORD-LEGACY-DIGEST");
        assertThat(replay.payableAmountCent()).isEqualTo(1234L);
    }

    private void assertConflict(long userId, AppOrderSubmitRequest request) {
        assertBusiness(ErrorCode.ORDER_STATE_CONFLICT,
                () -> appOrderService.submit(appPrincipal(userId), request));
    }

    private void assertBusiness(ErrorCode expected, Runnable action) {
        BusinessException exception = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(expected);
    }

    private AuthenticatedPrincipal appPrincipal(long userId) {
        return new AuthenticatedPrincipal(TokenKind.APP, userId, "user-" + userId, List.of(), List.of());
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

    private long insertAddress(
            long userId,
            String name,
            String phone,
            String province,
            String city,
            String district,
            String detail
    ) {
        jdbcClient.sql("""
                        insert into user_address
                            (user_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default)
                        values (:userId, :name, :phone, :province, :city, :district, :detail, false)
                        """)
                .param("userId", userId)
                .param("name", name)
                .param("phone", phone)
                .param("province", province)
                .param("city", city)
                .param("district", district)
                .param("detail", detail)
                .update();
        return jdbcClient.sql("select max(id) from user_address where user_id = :userId")
                .param("userId", userId).query(Long.class).single();
    }

    private long insertSku(String suffix, long priceCent, long originalPriceCent, int stock) {
        long sequence = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 1, 'ENABLED')
                        """)
                .param("name", "Order Category " + suffix + sequence)
                .update();
        long categoryId = jdbcClient.sql("select max(id) from product_category").query(Long.class).single();
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (:categoryId, :title, 'Order subtitle', :mainImage, '', '', 1, 'ON_SALE')
                        """)
                .param("categoryId", categoryId)
                .param("title", "Order SPU " + suffix)
                .param("mainImage", "https://example.test/" + suffix + "/main.jpg")
                .update();
        long spuId = jdbcClient.sql("select max(id) from product_spu").query(Long.class).single();
        String skuCode = suffix + "-" + sequence;
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order)
                        values (:spuId, :skuCode, '{}', '300g', :priceCent, :originalPriceCent,
                                :stock, 300, :image, 'ENABLED', 1)
                        """)
                .param("spuId", spuId)
                .param("skuCode", skuCode)
                .param("priceCent", priceCent)
                .param("originalPriceCent", originalPriceCent)
                .param("stock", stock)
                .param("image", "https://example.test/" + suffix + "/sku.jpg")
                .update();
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode).query(Long.class).single();
    }

    private long insertCartItem(long userId, long skuId, int quantity) {
        jdbcClient.sql("insert into cart_item (user_id, sku_id, quantity) values (:userId, :skuId, :quantity)")
                .param("userId", userId).param("skuId", skuId).param("quantity", quantity).update();
        return jdbcClient.sql("select id from cart_item where user_id = :userId and sku_id = :skuId")
                .param("userId", userId).param("skuId", skuId).query(Long.class).single();
    }

    private long insertCoupon(long userId, String name, long discountCent) {
        String uniqueName = name + " " + SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values (:name, '', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, :discountCent,
                                'ALL', '', 'coupon.amount-off.v1', 10, 1, 1,
                                :validStart, :validEnd, 'ENABLED', 1)
                        """)
                .param("name", uniqueName)
                .param("discountCent", discountCent)
                .param("validStart", now.minusDays(1))
                .param("validEnd", now.plusDays(1))
                .update();
        long templateId = jdbcClient.sql("select id from coupon_template where name = :name")
                .param("name", uniqueName).query(Long.class).single();
        jdbcClient.sql("""
                        insert into user_coupon
                            (user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value,
                             valid_start_at, valid_end_at, status, claimed_at)
                        values (:userId, :templateId, :name, 'NO_THRESHOLD', 'AMOUNT_OFF',
                                0, :discountCent, 'ALL', '', :validStart, :validEnd, 'CLAIMED', :now)
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("name", uniqueName)
                .param("discountCent", discountCent)
                .param("validStart", now.minusDays(1))
                .param("validEnd", now.plusDays(1))
                .param("now", now)
                .update();
        return jdbcClient.sql("select id from user_coupon where user_id = :userId and template_id = :templateId")
                .param("userId", userId).param("templateId", templateId).query(Long.class).single();
    }

    private void insertLegacyOrder(long userId, String key) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into shop_order
                            (order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, created_at, updated_at)
                        values ('ORD-LEGACY-DIGEST', :userId, 'CREATED', 'CART', :key, '',
                                1234, 1234, 0, 0, 1234, 0, :now, :now)
                        """)
                .param("userId", userId).param("key", key).param("now", now).update();
    }

    private long insertReadOrder(long userId, String status, LocalDateTime createdAt) {
        long orderId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                             1200, 1000, 0, 0, 1000, 1000,
                             'Read Receiver', '13800138000', 'Read Address', :createdAt, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "READ-" + orderId)
                .param("userId", userId)
                .param("status", status)
                .param("idempotencyKey", "read-" + orderId)
                .param("createdAt", createdAt)
                .update();
        return orderId;
    }

    private long insertDetailedOrder(
            long userId,
            String status,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            LocalDateTime shippedAt,
            LocalDateTime completedAt,
            LocalDateTime refundingAt,
            LocalDateTime refundedAt
    ) {
        long orderId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address,
                             payment_transaction_id, merchant_trade_no, paid_at, shipped_at,
                             completed_at, refunding_at, refunded_at, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'DIRECT', :idempotencyKey,
                             1500, 1400, 100, 50, 1350, 1350,
                             'Receiver Snapshot', '13800138000', 'Persisted Receiver Address',
                             :legacyTransactionId, :legacyTradeNo, :paidAt, :shippedAt,
                             :completedAt, :refundingAt, :refundedAt, :createdAt, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "DETAIL-" + orderId)
                .param("userId", userId)
                .param("status", status)
                .param("idempotencyKey", "detail-" + orderId)
                .param("legacyTransactionId", "legacy-tx-" + orderId)
                .param("legacyTradeNo", "legacy-trade-" + orderId)
                .param("paidAt", paidAt)
                .param("shippedAt", shippedAt)
                .param("completedAt", completedAt)
                .param("refundingAt", refundingAt)
                .param("refundedAt", refundedAt)
                .param("createdAt", createdAt)
                .update();
        return orderId;
    }

    private void insertPayment(
            long orderId,
            String outTradeNo,
            String transactionId,
            String status,
            LocalDateTime paidAt,
            LocalDateTime updatedAt
    ) {
        long paymentId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, status, amount_cent,
                             expires_at, paid_at, created_at, updated_at)
                        values
                            (:paymentId, :orderId, :outTradeNo, :transactionId, :status, 1350,
                             :expiresAt, :paidAt, :createdAt, :updatedAt)
                        """)
                .param("paymentId", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", outTradeNo)
                .param("transactionId", transactionId)
                .param("status", status)
                .param("expiresAt", updatedAt.plusMinutes(30))
                .param("paidAt", paidAt)
                .param("createdAt", updatedAt.minusMinutes(1))
                .param("updatedAt", updatedAt)
                .update();
    }

    private void insertShipment(long orderId, LocalDateTime shippedAt) {
        jdbcClient.sql("""
                        insert into order_shipment
                            (order_id, express_company_name, tracking_no, shipment_note,
                             status, wechat_upload_status, shipped_at)
                        values
                            (:orderId, 'Test Express', :trackingNo, 'left warehouse',
                             'SHIPPED', 'SUCCESS', :shippedAt)
                        """)
                .param("orderId", orderId)
                .param("trackingNo", "TRACK-" + orderId)
                .param("shippedAt", shippedAt)
                .update();
    }

    private long insertAfterSale(long orderId, long userId, String status, LocalDateTime createdAt) {
        long afterSaleId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, order_id, user_id, after_sale_type, status, reason,
                             description, requested_amount_cent, created_at, updated_at)
                        values
                            (:afterSaleId, :orderId, :userId, 'REFUND_ONLY', :status, 'detail truth',
                             '', 100, :createdAt, :createdAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", orderId)
                .param("userId", userId)
                .param("status", status)
                .param("createdAt", createdAt)
                .update();
        return afterSaleId;
    }

    private Map<Long, Integer> cartQuantities(long userId) {
        return jdbcClient.sql("select id, quantity from cart_item where user_id = :userId order by id")
                .param("userId", userId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getInt("quantity")))
                .list().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }

    private boolean cartRowExists(long cartItemId) {
        return jdbcClient.sql("select count(*) from cart_item where id = :id")
                .param("id", cartItemId).query(Long.class).single() == 1L;
    }

    private String orderSource(long orderId) {
        return jdbcClient.sql("select source from shop_order where id = :orderId")
                .param("orderId", orderId).query(String.class).single();
    }

    private void assertReceiverSnapshot(long orderId, String name, String phone, String address) {
        Map<String, Object> snapshot = jdbcClient.sql("""
                        select receiver_name, receiver_phone, receiver_address
                        from shop_order where id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "name", rs.getString("receiver_name"),
                        "phone", rs.getString("receiver_phone"),
                        "address", rs.getString("receiver_address")))
                .single();
        assertThat(snapshot).containsEntry("name", name).containsEntry("phone", phone).containsEntry("address", address);
    }

    private long count(String table, long orderId) {
        if (!"stock_lock".equals(table)) {
            throw new IllegalArgumentException("Unsupported table");
        }
        return jdbcClient.sql("select count(*) from stock_lock where order_id = :orderId")
                .param("orderId", orderId).query(Long.class).single();
    }
}
