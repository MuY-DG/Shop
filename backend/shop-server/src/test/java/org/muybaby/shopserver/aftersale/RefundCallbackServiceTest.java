package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.service.RefundCallbackService;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RefundCallbackServiceTest extends PaymentTestSupport {

    private static final Pattern OUT_REFUND_NO_PATTERN =
            Pattern.compile("\\\"out_refund_no\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Autowired
    private RefundCallbackService refundCallbackService;

    @Test
    void twoItemQuantityRefundsAccumulateAndRestockEachUnitExactlyOnce() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-partial-quantity-app");
        SeedPaidOrder order = seedPaidOrder(
                appUser, 6980L, "PAID", "wx-refund-partial-quantity");
        long orderItemId = jdbcClient.sql("select id from order_item where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Long.class)
                .single();
        String adminToken = adminLogin();

        String firstApply = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + appUser.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKey":"partial-quantity-1",
                                  "afterSaleType":"REFUND_ONLY",
                                  "reason":"部分退款第一件",
                                  "requestedAmountCent":3490,
                                  "items":[{"orderItemId":%d,"quantity":1}]
                                }
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedAmountCent").value(3490))
                .andReturn().getResponse().getContentAsString();
        long firstAfterSaleId = objectMapper.readTree(firstApply).path("data").path("id").asLong();
        mockMvc.perform(post("/admin/after-sales/{id}/approve", firstAfterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auditNote":"同意退第一件",
                                 "items":[{"orderItemId":%d,"approvedQuantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"));
        String firstRefundNo = jdbcClient.sql("""
                        select out_refund_no from refund_order where after_sale_id = :id
                        """)
                .param("id", firstAfterSaleId)
                .query(String.class)
                .single();
        assertThat(jdbcClient.sql("""
                        select concat(status, '|', refund_status, '|', refunded_amount_cent)
                        from shop_order where id = :id
                        """)
                .param("id", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID|PARTIAL_REFUNDING|0");

        String firstCallback = refundNotifyBody(
                "notify-partial-quantity-1", "REFUND.SUCCESS", order.outTradeNo(),
                firstRefundNo, "wx-partial-quantity-1", "SUCCESS", 3490L, 6980L);
        postRefundNotify(firstCallback, "mock-valid-signature").andExpect(status().isOk());
        postRefundNotify(firstCallback, "mock-valid-signature").andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                        select concat(status, '|', refund_status, '|', refunded_amount_cent)
                        from shop_order where id = :id
                        """)
                .param("id", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID|PARTIALLY_REFUNDED|3490");
        assertThat(jdbcClient.sql("""
                        select concat(refunded_quantity, '|', status, '|', restocked_quantity)
                        from order_item item
                        join stock_lock lock_entry on lock_entry.order_item_id = item.id
                        where item.id = :itemId
                        """)
                .param("itemId", orderItemId)
                .query(String.class)
                .single()).isEqualTo("1|PARTIALLY_RESTOCKED|1");

        String secondApply = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + appUser.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKey":"partial-quantity-2",
                                  "afterSaleType":"REFUND_ONLY",
                                  "reason":"部分退款第二件",
                                  "requestedAmountCent":3490,
                                  "items":[{"orderItemId":%d,"quantity":1}]
                                }
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long secondAfterSaleId = objectMapper.readTree(secondApply).path("data").path("id").asLong();
        mockMvc.perform(post("/admin/after-sales/{id}/approve", secondAfterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auditNote":"同意退第二件",
                                 "items":[{"orderItemId":%d,"approvedQuantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isOk());
        String secondRefundNo = jdbcClient.sql("""
                        select out_refund_no from refund_order where after_sale_id = :id
                        """)
                .param("id", secondAfterSaleId)
                .query(String.class)
                .single();
        postRefundNotify(refundNotifyBody(
                        "notify-partial-quantity-2", "REFUND.SUCCESS", order.outTradeNo(),
                        secondRefundNo, "wx-partial-quantity-2", "SUCCESS", 3490L, 6980L),
                "mock-valid-signature").andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                        select concat(status, '|', refund_status, '|', refunded_amount_cent)
                        from shop_order where id = :id
                        """)
                .param("id", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDED|FULLY_REFUNDED|6980");
        assertThat(jdbcClient.sql("""
                        select concat(refunded_quantity, '|', status, '|', restocked_quantity)
                        from order_item item
                        join stock_lock lock_entry on lock_entry.order_item_id = item.id
                        where item.id = :itemId
                        """)
                .param("itemId", orderItemId)
                .query(String.class)
                .single()).isEqualTo("2|RESTOCKED|2");
        assertThat(jdbcClient.sql("""
                        select coalesce(sum(quantity), 0)
                        from refund_inventory_restock_item
                        where order_item_id = :itemId
                        """)
                .param("itemId", orderItemId)
                .query(Long.class)
                .single()).isEqualTo(2L);
        assertThat(jdbcClient.sql("""
                        select sku.stock_available from product_sku sku
                        join order_item item on item.sku_id = sku.id
                        where item.id = :itemId
                        """)
                .param("itemId", orderItemId)
                .query(Integer.class)
                .single()).isEqualTo(10);
    }

    @Test
    void successfulRefundNotificationFinalizesRefundAndDuplicateNotificationIsIdempotent() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-success", 6980L, 6980L);
        String body = refundNotifyBody(
                "notify-refund-success",
                "REFUND.SUCCESS",
                approved.outTradeNo(),
                approved.outRefundNo(),
                "wx-refund-success",
                "SUCCESS",
                6980L,
                6980L
        );

        postRefundNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertRefundSuccessState(approved, "wx-refund-success");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and out_refund_no = :outRefundNo
                          and refund_id = 'wx-refund-success'
                          and status = 'SUCCESS'
                          and resource_digest not like '%wx-refund-success%'
                          and raw_body_sha256 <> ''
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);

        postRefundNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertRefundSuccessState(approved, "wx-refund-success");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and out_refund_no = :outRefundNo
                          and status = 'DUPLICATE'
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void shippedOrderRefundDoesNotRestockSellableInventory() throws Exception {
        ApprovedRefund approved = approveRefund(
                "after-sale-refund-shipped", 6980L, 6980L, "SHIPPED");

        postRefundNotify(refundNotifyBody(
                        "notify-refund-shipped",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-shipped",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                        select count(*) from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'SUCCESS'
                          and restock_required = false
                          and restocked_at is null
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("CONFIRMED");
        assertThat(jdbcClient.sql("""
                        select sku.stock_available
                        from product_sku sku
                        join stock_lock lock_entry on lock_entry.sku_id = sku.id
                        where lock_entry.order_id = :orderId
                        """)
                .param("orderId", approved.orderId())
                .query(Integer.class)
                .single()).isEqualTo(8);
        assertThat(jdbcClient.sql("""
                        select count(*) from stock_log
                        where order_id = :orderId and change_type = 'REFUND_RESTOCK'
                        """)
                .param("orderId", approved.orderId())
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void incompleteConfirmedLockMappingRollsBackRefundSuccessAndInventoryMutation() throws Exception {
        ApprovedRefund approved = approveRefund(
                "after-sale-refund-invalid-lock-map", 6980L, 6980L);
        jdbcClient.sql("""
                        update stock_lock
                        set quantity = quantity - 1
                        where order_id = :orderId
                        """)
                .param("orderId", approved.orderId())
                .update();

        postRefundNotify(refundNotifyBody(
                        "notify-refund-invalid-lock-map",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-invalid-lock-map",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("""
                        select count(*) from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'PROCESSING'
                          and restock_required = true
                          and restocked_at is null
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("CONFIRMED");
        assertThat(jdbcClient.sql("""
                        select sku.stock_available
                        from product_sku sku
                        join stock_lock stock_lock_entry on stock_lock_entry.sku_id = sku.id
                        where stock_lock_entry.order_id = :orderId
                        """)
                .param("orderId", approved.orderId())
                .query(Integer.class)
                .single()).isEqualTo(8);
        assertThat(jdbcClient.sql("""
                        select count(*) from stock_log
                        where order_id = :orderId and change_type = 'REFUND_RESTOCK'
                        """)
                .param("orderId", approved.orderId())
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void routedRefundCallbackUsesThePaymentsBoundConfigurationAfterRotation() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-old-config", 6980L, 6980L);
        switchToClonedPaymentConfig(91002L);
        mockWechatPayProvider.requireRefundNotificationConfig(approved.outRefundNo(), 91001L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-old-config",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-old-config",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isOk());

        assertRefundSuccessState(approved, "wx-refund-old-config");
        assertThat(mockWechatPayProvider.refundNotificationConfigAttempts(approved.outRefundNo()))
                .containsExactly(91001L);
    }

    @Test
    void routedRefundCallbackParsesExactlyOnceWithTheBoundConfiguration() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-shared-key", 6980L, 6980L);
        switchToClonedPaymentConfig(91002L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-shared-key",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-shared-key",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isOk());

        assertRefundSuccessState(approved, "wx-refund-shared-key");
        assertThat(mockWechatPayProvider.refundNotificationConfigAttempts(approved.outRefundNo()))
                .containsExactly(91001L);
    }

    @Test
    void routedRefundNotificationNeverFallsBackToTheCurrentConfiguration() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-config-mismatch", 6980L, 6980L);
        switchToClonedPaymentConfig(91002L);
        mockWechatPayProvider.requireRefundNotificationConfig(approved.outRefundNo(), 91002L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-wrong-config",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-wrong-config",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", approved.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("PROCESSING");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and out_refund_no = ''
                          and status = 'FAILED'
                          and error_code = 'VERIFY_FAILED'
                        """)
                .query(Integer.class)
                .single()).isZero();
        assertThat(mockWechatPayProvider.refundNotificationConfigAttempts(approved.outRefundNo()))
                .containsExactly(91001L);
    }

    @Test
    void concurrentIdenticalNotificationsFinalizeTheirOwnCallbackLogRows() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-concurrent", 6980L, 6980L);
        String body = refundNotifyBody(
                "notify-refund-concurrent",
                "REFUND.SUCCESS",
                approved.outTradeNo(),
                approved.outRefundNo(),
                "wx-refund-concurrent",
                "SUCCESS",
                6980L,
                6980L
        );
        int callbackCount = 6;
        String notificationTimestamp = currentWechatpayTimestamp();
        CountDownLatch ready = new CountDownLatch(callbackCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callbackCount);
        List<Future<?>> callbacks = new ArrayList<>();
        try {
            for (int index = 0; index < callbackCount; index++) {
                callbacks.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent callbacks did not start in time");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Concurrent callback was interrupted", ex);
                    }
                    refundCallbackService.handleRefundNotification(
                            refundRouteToken(approved.outRefundNo()),
                            notificationTimestamp,
                            "mock-refund-notify-nonce",
                            "mock-refund-serial",
                            "mock-valid-signature",
                            body
                    );
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> callback : callbacks) {
                callback.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertRefundSuccessState(approved, "wx-refund-concurrent");
        assertThat(callbackLogCount(approved.outRefundNo(), null)).isEqualTo(callbackCount);
        assertThat(callbackLogCount(approved.outRefundNo(), "PROCESSING")).isZero();
        assertThat(callbackLogCount(approved.outRefundNo(), "SUCCESS")).isEqualTo(1);
        assertThat(callbackLogCount(approved.outRefundNo(), "DUPLICATE")).isEqualTo(callbackCount - 1);
    }

    @Test
    void failedNotificationAfterSuccessfulRefundIsIgnoredWithoutDowngradingState() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-success-then-failed", 6980L, 6980L);
        postRefundNotify(refundNotifyBody(
                        "notify-refund-success-before-late-failure",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-success-before-late-failure",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertRefundSuccessState(approved, "wx-refund-success-before-late-failure");

        postRefundNotify(refundNotifyBody(
                        "notify-refund-late-abnormal",
                        "REFUND.ABNORMAL",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-late-abnormal",
                        "ABNORMAL",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertRefundSuccessState(approved, "wx-refund-success-before-late-failure");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and notify_id = 'notify-refund-late-abnormal'
                          and out_refund_no = :outRefundNo
                          and status = 'DUPLICATE'
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void failedRefundNotificationMarksRefundFailedAndKeepsOrderRefunding() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-failed", 6980L, 6980L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-failed",
                        "REFUND.ABNORMAL",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-failed",
                        "ABNORMAL",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'FAILED'
                          and refund_id = 'wx-refund-failed'
                          and callback_status = 'ABNORMAL'
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", approved.afterSaleId())
                .query(String.class)
                .single()).isEqualTo("REFUND_FAILED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
    }

    @Test
    void callbackForSupersededFailedRefundCannotFinalizeOverNewerRefund() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-superseded", 6980L, 6980L);
        String newerOutRefundNo = approved.outRefundNo() + "N";
        jdbcClient.sql("""
                        update refund_order
                        set status = 'FAILED',
                            callback_status = 'CLOSED',
                            failed_at = current_timestamp,
                            updated_at = current_timestamp
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .update();
        jdbcClient.sql("""
                        insert into refund_order
                            (after_sale_id, order_id, payment_order_id, notification_route_token,
                             out_refund_no, refund_id,
                             refund_amount_cent, status, callback_status, requested_at, created_at, updated_at)
                        select after_sale_id, order_id, payment_order_id,
                               'NNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN', :newerOutRefundNo, '',
                               refund_amount_cent, 'PROCESSING', 'PROCESSING',
                               current_timestamp, current_timestamp, current_timestamp
                        from refund_order
                        where out_refund_no = :outRefundNo
                        """)
                .param("newerOutRefundNo", newerOutRefundNo)
                .param("outRefundNo", approved.outRefundNo())
                .update();

        postRefundNotify(refundNotifyBody(
                        "notify-refund-superseded",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-superseded",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", approved.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("FAILED");
        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", newerOutRefundNo)
                .query(String.class)
                .single()).isEqualTo("PROCESSING");
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", approved.afterSaleId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and notify_id = 'notify-refund-superseded'
                          and status = 'FAILED'
                          and error_code = 'ORDER_STATE_CONFLICT'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void invalidRefundNotificationIsNotPersistedAndDoesNotChangeRefundState() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-invalid", 6980L, 6980L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-invalid",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-invalid",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "bad-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", approved.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("PROCESSING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and status = 'FAILED'
                          and error_message not like '%wx-refund-invalid%'
                          and resource_digest not like '%wx-refund-invalid%'
                        """)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void rejectedRefundTransitionPersistsFailedCallbackLogOutsideBusinessTransaction() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-mismatch", 6980L, 6980L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-mismatch",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-mismatch",
                        "SUCCESS",
                        6979L,
                        6980L
                ), "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", approved.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("PROCESSING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and notify_id = 'notify-refund-mismatch'
                          and status = 'FAILED'
                          and error_code = 'ORDER_STATE_CONFLICT'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void staleRefundNotificationIsRejectedBeforeVerificationOrBusinessMutation() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-stale", 6980L, 6980L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-stale",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-stale",
                        "SUCCESS",
                        6980L,
                        6980L
                ), "mock-valid-signature", "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", approved.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("PROCESSING");
        assertThat(jdbcClient.sql("select count(*) from payment_callback_log")
                .query(Integer.class)
                .single()).isZero();
    }

    private ApprovedRefund approveRefund(String code, long paidAmountCent, long approvedAmountCent) throws Exception {
        return approveRefund(code, paidAmountCent, approvedAmountCent, "PAID");
    }

    private ApprovedRefund approveRefund(
            String code,
            long paidAmountCent,
            long approvedAmountCent,
            String orderStatus
    ) throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin(code + "-app");
        SeedPaidOrder order = seedPaidOrder(appUser, paidAmountCent, orderStatus, "wx-refund-" + code);
        long evidenceFileId = insertAppEvidenceFile(appUser.userId(), order.orderId());
        String applyResponse = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + appUser.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "退款回调测试", approvedAmountCent, "callback test", evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(applyResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":%d,"auditNote":"同意退款"}
                                """.formatted(approvedAmountCent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"));

        String outRefundNo = jdbcClient.sql("select out_refund_no from refund_order where after_sale_id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single();
        return new ApprovedRefund(afterSaleId, order.orderId(), order.outTradeNo(), outRefundNo);
    }

    private org.springframework.test.web.servlet.ResultActions postRefundNotify(String body, String signature) throws Exception {
        return postRefundNotify(body, signature, currentWechatpayTimestamp());
    }

    private org.springframework.test.web.servlet.ResultActions postRefundNotify(
            String body,
            String signature,
            String timestamp
    ) throws Exception {
        String outRefundNo = requiredJsonField(OUT_REFUND_NO_PATTERN, body);
        return mockMvc.perform(post("/wxpay/refund/notify/r/{routeToken}", refundRouteToken(outRefundNo))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", timestamp)
                .header("Wechatpay-Nonce", "mock-refund-notify-nonce")
                .header("Wechatpay-Serial", "mock-refund-serial")
                .header("Wechatpay-Signature", signature)
                .content(body));
    }

    private String refundRouteToken(String outRefundNo) {
        return jdbcClient.sql("""
                        select notification_route_token from refund_order
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", outRefundNo)
                .query(String.class)
                .single();
    }

    private String requiredJsonField(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Required notification identity is missing");
        }
        return matcher.group(1);
    }

    private String applyBody(String type, String reason, long requestedAmountCent, String description, long... fileIds) {
        String evidenceFileIds = Arrays.stream(fileIds)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        return """
                {"afterSaleType":"%s","reason":"%s","requestedAmountCent":%d,
                 "description":"%s","evidenceFileIds":[%s]}
                """.formatted(type, reason, requestedAmountCent, description, evidenceFileIds);
    }

    private String refundNotifyBody(
            String notifyId,
            String eventType,
            String outTradeNo,
            String outRefundNo,
            String refundId,
            String refundStatus,
            long refundAmountCent,
            long totalAmountCent
    ) {
        return """
                {
                  "id":"%s",
                  "event_type":"%s",
                  "resource":{
                    "out_trade_no":"%s",
                    "out_refund_no":"%s",
                    "refund_id":"%s",
                    "refund_status":"%s",
                    "success_time":"2026-07-08T14:00:00+08:00",
                    "amount":{"refund":%d,"total":%d,"currency":"CNY"}
                  }
                }
                """.formatted(notifyId, eventType, outTradeNo, outRefundNo, refundId, refundStatus, refundAmountCent, totalAmountCent);
    }

    private void assertRefundSuccessState(ApprovedRefund approved, String refundId) {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'SUCCESS'
                          and refund_id = :refundId
                          and callback_status = 'SUCCESS'
                          and success_at is not null
                          and restock_required = true
                          and restocked_at is not null
                          and callback_digest <> ''
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .param("refundId", refundId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", approved.afterSaleId())
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", approved.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from stock_lock
                        where order_id = :orderId
                          and status = 'RESTOCKED'
                          and restock_refund_order_id = (
                            select id from refund_order where out_refund_no = :outRefundNo
                          )
                          and restocked_at is not null
                        """)
                .param("orderId", approved.orderId())
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select sku.stock_available
                        from product_sku sku
                        join stock_lock lock_entry on lock_entry.sku_id = sku.id
                        where lock_entry.order_id = :orderId
                        """)
                .param("orderId", approved.orderId())
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*) from stock_log
                        where order_id = :orderId
                          and change_type = 'REFUND_RESTOCK'
                          and refund_order_id = (
                            select id from refund_order where out_refund_no = :outRefundNo
                          )
                        """)
                .param("orderId", approved.orderId())
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private int callbackLogCount(String outRefundNo, String status) {
        return jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and out_refund_no = :outRefundNo
                          and (:status is null or status = :status)
                        """)
                .param("outRefundNo", outRefundNo)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private record ApprovedRefund(long afterSaleId, long orderId, String outTradeNo, String outRefundNo) {
    }
}
