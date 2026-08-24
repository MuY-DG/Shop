package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.service.PaymentCallbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentCallbackServiceTest extends PaymentTestSupport {

    private static final Pattern OUT_TRADE_NO_PATTERN =
            Pattern.compile("\\\"out_trade_no\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Autowired
    private PaymentCallbackService paymentCallbackService;

    @Test
    void validPayNotificationFinalizesOrderAndDuplicateNotificationIsIdempotent() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-callback-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        jdbcClient.sql("""
                        insert into payment_attempt
                            (order_id, out_trade_no, status, amount_cent, error_code, error_message,
                             started_at, created_at, updated_at)
                        values
                            (:orderId, :outTradeNo, 'PREPAY_FAILED', 6980, 'PROVIDER_ERROR', 'previous failure',
                             timestamp '2026-07-08 11:00:00', timestamp '2026-07-08 11:00:00',
                             timestamp '2026-07-08 11:00:01')
                        """)
                .param("orderId", order.orderId())
                .param("outTradeNo", outTradeNo)
                .update();
        String body = payNotifyBody("notify-payment-1", outTradeNo, "wx-transaction-callback", 6980L);

        postPayNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertPaidState(order, outTradeNo, "wx-transaction-callback");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt a
                        join payment_order p on p.id = a.payment_order_id
                        where a.out_trade_no = :outTradeNo
                          and a.status = 'PAID'
                          and p.status = 'PAID'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt
                        where out_trade_no = :outTradeNo
                          and status = 'PREPAY_FAILED'
                          and payment_order_id is null
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from payment_callback_log where out_trade_no = :outTradeNo and status = 'SUCCESS'")
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);

        postPayNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertPaidState(order, outTradeNo, "wx-transaction-callback");
        assertThat(jdbcClient.sql("select count(*) from payment_order where out_trade_no = :outTradeNo and status = 'PAID'")
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from payment_callback_log where out_trade_no = :outTradeNo and status = 'DUPLICATE'")
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void routedCallbackUsesThePaymentOrdersBoundConfigurationAfterRotation() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-callback-config-rotation-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        switchToClonedPaymentConfig(91002L);
        mockWechatPayProvider.requirePayNotificationConfig(outTradeNo, 91001L);

        postPayNotify(
                payNotifyBody("notify-payment-old-config", outTradeNo, "wx-old-config", 6980L),
                "mock-valid-signature"
        ).andExpect(status().isOk());

        assertPaidState(order, outTradeNo, "wx-old-config");
        assertThat(mockWechatPayProvider.payNotificationConfigAttempts(outTradeNo))
                .containsExactly(91001L);
    }

    @Test
    void routedCallbackParsesExactlyOnceWithTheBoundConfiguration() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-callback-shared-key-rotation-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        switchToClonedPaymentConfig(91002L);

        postPayNotify(
                payNotifyBody("notify-payment-shared-key", outTradeNo, "wx-shared-key", 6980L),
                "mock-valid-signature"
        ).andExpect(status().isOk());

        assertPaidState(order, outTradeNo, "wx-shared-key");
        assertThat(mockWechatPayProvider.payNotificationConfigAttempts(outTradeNo))
                .containsExactly(91001L);
    }

    @Test
    void routedNotificationNeverFallsBackToTheCurrentConfiguration() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-callback-config-mismatch-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        switchToClonedPaymentConfig(91002L);
        mockWechatPayProvider.requirePayNotificationConfig(outTradeNo, 91002L);

        postPayNotify(
                payNotifyBody("notify-payment-wrong-config", outTradeNo, "wx-wrong-config", 6980L),
                "mock-valid-signature"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertPayingState(order, outTradeNo);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'PAY'
                          and out_trade_no = ''
                          and status = 'FAILED'
                          and error_code = 'VERIFY_FAILED'
                        """)
                .query(Integer.class)
                .single()).isZero();
        assertThat(mockWechatPayProvider.payNotificationConfigAttempts(outTradeNo))
                .containsExactly(91001L);
    }

    @Test
    void concurrentIdenticalNotificationsFinalizeTheirOwnCallbackLogRows() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-concurrent-callback-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        String body = payNotifyBody(
                "notify-payment-concurrent",
                outTradeNo,
                "wx-transaction-concurrent",
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
                    paymentCallbackService.handlePayNotification(
                            paymentRouteToken(outTradeNo),
                            notificationTimestamp,
                            "mock-notify-nonce",
                            "mock-serial",
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

        assertPaidState(order, outTradeNo, "wx-transaction-concurrent");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(callbackCount);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'PROCESSING'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'SUCCESS'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'DUPLICATE'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(callbackCount - 1);
    }

    @Test
    void invalidPayNotificationReturnsFailureWithoutPersistingUnverifiedInput() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-invalid-callback-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        String body = payNotifyBody("notify-payment-invalid", outTradeNo, "wx-transaction-secret", 6980L);

        postPayNotify(body, "bad-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAYING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where status = 'FAILED'
                          and error_message not like '%api_v3_secret_test%'
                          and error_message not like '%wx-transaction-secret%'
                          and resource_digest not like '%wx-transaction-secret%'
                        """)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void stalePayNotificationIsRejectedBeforeVerificationOrBusinessMutation() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-stale-callback-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());

        postPayNotify(
                payNotifyBody("notify-payment-stale", outTradeNo, "wx-transaction-stale", 6980L),
                "mock-valid-signature",
                "1"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertPayingState(order, outTradeNo);
        assertThat(jdbcClient.sql("select count(*) from payment_callback_log")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void nonSuccessPayNotificationFailsWithoutChangingPaymentState() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-non-success-callback-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());

        postPayNotify(payNotifyBody(
                        "notify-payment-closed",
                        "TRANSACTION.CLOSED",
                        outTradeNo,
                        "wx-transaction-closed",
                        6980L,
                        "CLOSED"
                ), "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertPayingState(order, outTradeNo);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and transaction_id = 'wx-transaction-closed'
                          and status = 'FAILED'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void notificationAmountMismatchFailsWithoutChangingPaymentState() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-amount-mismatch-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());

        postPayNotify(payNotifyBody("notify-payment-mismatch", outTradeNo, "wx-transaction-mismatch", 1L),
                        "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select status from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(String.class)
                .single()).isEqualTo("PAYING");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAYING");
    }

    @Test
    void successfulNotificationRequiresTransactionIdAndCnyCurrency() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-callback-required-fields-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());

        postPayNotify(
                payNotifyBody("notify-payment-empty-transaction", outTradeNo, "", 6980L),
                "mock-valid-signature"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        postPayNotify(
                payNotifyBody("notify-payment-wrong-currency", "TRANSACTION.SUCCESS", outTradeNo,
                        "wx-wrong-currency", 6980L, "SUCCESS", "USD"),
                "mock-valid-signature"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertPayingState(order, outTradeNo);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where notify_id in ('notify-payment-empty-transaction', 'notify-payment-wrong-currency')
                          and status = 'FAILED'
                          and error_code = 'ORDER_STATE_CONFLICT'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void corruptedStockLockMappingRollsBackPaidTransition() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-stock-lock-mismatch-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        jdbcClient.sql("""
                        update stock_lock
                        set sku_id = sku_id + 999999
                        where order_id = :orderId
                        """)
                .param("orderId", order.orderId())
                .update();

        postPayNotify(
                payNotifyBody("notify-payment-stock-lock-mismatch", outTradeNo, "wx-stock-lock-mismatch", 6980L),
                "mock-valid-signature"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertPayingState(order, outTradeNo);
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("LOCKED");
    }

    @Test
    void duplicateNotificationStillRejectsMismatchedAmountOrTransaction() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-duplicate-mismatch-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        String successBody = payNotifyBody("notify-payment-dup-success", outTradeNo, "wx-transaction-duplicate", 6980L);

        postPayNotify(successBody, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        postPayNotify(successBody, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        postPayNotify(payNotifyBody("notify-payment-dup-amount", outTradeNo, "wx-transaction-duplicate", 1L),
                        "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        postPayNotify(payNotifyBody("notify-payment-dup-transaction", outTradeNo, "wx-transaction-different", 6980L),
                        "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertPaidState(order, outTradeNo, "wx-transaction-duplicate");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'DUPLICATE'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'FAILED'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void duplicatePayNotificationAfterShippedOrderRemainsIdempotent() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-duplicate-shipped-user");
        SeedPaidOrder order = seedPaidOrder(session, 6980L, "SHIPPED", "wx-transaction-shipped-duplicate");
        String body = payNotifyBody("notify-payment-shipped-duplicate", order.outTradeNo(), order.transactionId(), order.paidAmountCent());

        postPayNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertPostPaymentDuplicateState(order, "SHIPPED");
    }

    @Test
    void duplicatePayNotificationAfterRefundingOrderRemainsIdempotent() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-duplicate-refunding-user");
        SeedPaidOrder order = seedPaidOrder(session, 6980L, "REFUNDING", "wx-transaction-refunding-duplicate");
        String body = payNotifyBody("notify-payment-refunding-duplicate", order.outTradeNo(), order.transactionId(), order.paidAmountCent());

        postPayNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertPostPaymentDuplicateState(order, "REFUNDING");
    }

    @Test
    void duplicatePayNotificationAfterClosedOrderIsRejected() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-duplicate-closed-user");
        SeedPaidOrder order = seedPaidOrder(session, 6980L, "CLOSED", "wx-transaction-closed-duplicate");
        String body = payNotifyBody("notify-payment-closed-duplicate", order.outTradeNo(), order.transactionId(), order.paidAmountCent());

        postPayNotify(body, "mock-valid-signature")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'FAILED'
                        """)
                .param("outTradeNo", order.outTradeNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions postPayNotify(String body, String signature) throws Exception {
        return postPayNotify(body, signature, currentWechatpayTimestamp());
    }

    private org.springframework.test.web.servlet.ResultActions postPayNotify(
            String body,
            String signature,
            String timestamp
    ) throws Exception {
        String outTradeNo = requiredJsonField(OUT_TRADE_NO_PATTERN, body);
        return mockMvc.perform(post("/wxpay/pay/notify/r/{routeToken}", paymentRouteToken(outTradeNo))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", timestamp)
                .header("Wechatpay-Nonce", "mock-notify-nonce")
                .header("Wechatpay-Serial", "mock-serial")
                .header("Wechatpay-Signature", signature)
                .content(body));
    }

    private String paymentRouteToken(String outTradeNo) {
        return jdbcClient.sql("""
                        select notification_route_token from payment_order
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
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

    private String payNotifyBody(String notifyId, String outTradeNo, String transactionId, long amountCent) {
        return payNotifyBody(notifyId, "TRANSACTION.SUCCESS", outTradeNo, transactionId, amountCent, "SUCCESS");
    }

    private String payNotifyBody(
            String notifyId,
            String eventType,
            String outTradeNo,
            String transactionId,
            long amountCent,
            String tradeState
    ) {
        return payNotifyBody(notifyId, eventType, outTradeNo, transactionId, amountCent, tradeState, "CNY");
    }

    private String payNotifyBody(
            String notifyId,
            String eventType,
            String outTradeNo,
            String transactionId,
            long amountCent,
            String tradeState,
            String currency
    ) {
        return """
                {
                  "id":"%s",
                  "event_type":"%s",
                  "resource":{
                    "out_trade_no":"%s",
                    "transaction_id":"%s",
                    "trade_state":"%s",
                    "success_time":"2026-07-08T12:00:00+08:00",
                    "amount":{"total":%d,"payer_total":%d,"currency":"%s"}
                  }
                }
                """.formatted(
                notifyId, eventType, outTradeNo, transactionId, tradeState, amountCent, amountCent, currency);
    }

    private void assertPaidState(SeedOrder order, String outTradeNo, String transactionId) {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_order
                        where out_trade_no = :outTradeNo
                          and status = 'PAID'
                          and transaction_id = :transactionId
                          and paid_at is not null
                        """)
                .param("outTradeNo", outTradeNo)
                .param("transactionId", transactionId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where id = :orderId
                          and status = 'PAID'
                          and paid_amount_cent = 6980
                          and paid_at is not null
                          and payment_transaction_id = :transactionId
                          and merchant_trade_no = :outTradeNo
                        """)
                .param("orderId", order.orderId())
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CONFIRMED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where id = :userCouponId
                          and status = 'USED'
                          and used_order_id = :orderId
                          and used_at is not null
                        """)
                .param("userCouponId", order.userCouponId())
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private void assertPayingState(SeedOrder order, String outTradeNo) {
        assertThat(jdbcClient.sql("select status from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(String.class)
                .single()).isEqualTo("PAYING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where id = :orderId
                          and status = 'PAYING'
                          and paid_amount_cent = 0
                          and paid_at is null
                          and payment_transaction_id = ''
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("LOCKED");
        assertThat(jdbcClient.sql("select status from user_coupon where id = :userCouponId")
                .param("userCouponId", order.userCouponId())
                .query(String.class)
                .single()).isEqualTo("LOCKED");
    }

    private void assertPostPaymentDuplicateState(SeedPaidOrder order, String expectedOrderStatus) {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_order
                        where out_trade_no = :outTradeNo
                          and status = 'PAID'
                          and transaction_id = :transactionId
                          and amount_cent = :amountCent
                        """)
                .param("outTradeNo", order.outTradeNo())
                .param("transactionId", order.transactionId())
                .param("amountCent", order.paidAmountCent())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where id = :orderId
                          and status = :expectedOrderStatus
                          and paid_amount_cent = :amountCent
                          and payment_transaction_id = :transactionId
                          and merchant_trade_no = :outTradeNo
                        """)
                .param("orderId", order.orderId())
                .param("expectedOrderStatus", expectedOrderStatus)
                .param("amountCent", order.paidAmountCent())
                .param("transactionId", order.transactionId())
                .param("outTradeNo", order.outTradeNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and status = 'DUPLICATE'
                        """)
                .param("outTradeNo", order.outTradeNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }
}
