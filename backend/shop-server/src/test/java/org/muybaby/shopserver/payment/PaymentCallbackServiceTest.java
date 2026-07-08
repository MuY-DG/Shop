package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentCallbackServiceTest extends PaymentTestSupport {

    @Test
    void validPayNotificationFinalizesOrderAndDuplicateNotificationIsIdempotent() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-callback-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        String body = payNotifyBody("notify-payment-1", outTradeNo, "wx-transaction-callback", 6980L);

        postPayNotify(body, "mock-valid-signature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertPaidState(order, outTradeNo, "wx-transaction-callback");
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
    void invalidPayNotificationReturnsFailureAndDoesNotPersistPlaintextSecretMaterial() throws Exception {
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
                .single()).isEqualTo(1);
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
        return mockMvc.perform(post("/wxpay/pay/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", "1783500000")
                .header("Wechatpay-Nonce", "mock-notify-nonce")
                .header("Wechatpay-Serial", "mock-serial")
                .header("Wechatpay-Signature", signature)
                .content(body));
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
        return """
                {
                  "id":"%s",
                  "event_type":"%s",
                  "resource":{
                    "out_trade_no":"%s",
                    "transaction_id":"%s",
                    "trade_state":"%s",
                    "success_time":"2026-07-08T12:00:00+08:00",
                    "amount":{"total":%d,"payer_total":%d,"currency":"CNY"}
                  }
                }
                """.formatted(notifyId, eventType, outTradeNo, transactionId, tradeState, amountCent, amountCent);
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
