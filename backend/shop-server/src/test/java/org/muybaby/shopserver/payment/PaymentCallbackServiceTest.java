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
        return """
                {
                  "id":"%s",
                  "event_type":"TRANSACTION.SUCCESS",
                  "resource":{
                    "out_trade_no":"%s",
                    "transaction_id":"%s",
                    "success_time":"2026-07-08T12:00:00+08:00",
                    "amount":{"total":%d,"payer_total":%d,"currency":"CNY"}
                  }
                }
                """.formatted(notifyId, outTradeNo, transactionId, amountCent, amountCent);
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
}
