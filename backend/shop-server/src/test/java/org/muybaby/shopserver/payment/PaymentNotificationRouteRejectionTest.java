package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentNotificationRouteRejectionTest extends PaymentTestSupport {

    @Test
    void unknownWellFormedPayRouteDoesNotCreateARejectedCallbackLog() throws Exception {
        String outTradeNo = "UNKNOWN-ROUTE-PAYMENT";

        postRoutedPay("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", payBody(outTradeNo))
                .andExpect(status().isBadRequest());

        assertThat(callbackLogCount()).isZero();
        assertThat(mockWechatPayProvider.payNotificationConfigAttempts(outTradeNo)).isEmpty();
    }

    @Test
    void malformedRefundRouteDoesNotCreateARejectedCallbackLog() throws Exception {
        String outRefundNo = "MALFORMED-ROUTE-REFUND";

        postRoutedRefund("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", refundBody(outRefundNo))
                .andExpect(status().isBadRequest());

        assertThat(callbackLogCount()).isZero();
        assertThat(mockWechatPayProvider.refundNotificationConfigAttempts(outRefundNo)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions postRoutedPay(
            String routeToken,
            String body
    ) throws Exception {
        return mockMvc.perform(post("/wxpay/pay/notify/r/{routeToken}", routeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", currentWechatpayTimestamp())
                .header("Wechatpay-Nonce", "route-rejection-pay-nonce")
                .header("Wechatpay-Serial", "route-rejection-pay-serial")
                .header("Wechatpay-Signature", "mock-valid-signature")
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions postRoutedRefund(
            String routeToken,
            String body
    ) throws Exception {
        return mockMvc.perform(post("/wxpay/refund/notify/r/{routeToken}", routeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", currentWechatpayTimestamp())
                .header("Wechatpay-Nonce", "route-rejection-refund-nonce")
                .header("Wechatpay-Serial", "route-rejection-refund-serial")
                .header("Wechatpay-Signature", "mock-valid-signature")
                .content(body));
    }

    private int callbackLogCount() {
        return jdbcClient.sql("select count(*) from payment_callback_log")
                .query(Integer.class)
                .single();
    }

    private String payBody(String outTradeNo) {
        return """
                {
                  "id":"notify-route-rejection-pay",
                  "event_type":"TRANSACTION.SUCCESS",
                  "resource":{
                    "out_trade_no":"%s",
                    "transaction_id":"transaction-route-rejection",
                    "trade_state":"SUCCESS",
                    "success_time":"2026-07-08T12:00:00+08:00",
                    "amount":{"total":100,"currency":"CNY"}
                  }
                }
                """.formatted(outTradeNo);
    }

    private String refundBody(String outRefundNo) {
        return """
                {
                  "id":"notify-route-rejection-refund",
                  "event_type":"REFUND.SUCCESS",
                  "resource":{
                    "out_trade_no":"UNKNOWN-ROUTE-TRADE",
                    "out_refund_no":"%s",
                    "refund_id":"refund-route-rejection",
                    "refund_status":"SUCCESS",
                    "success_time":"2026-07-08T14:00:00+08:00",
                    "amount":{"refund":100,"total":100,"currency":"CNY"}
                  }
                }
                """.formatted(outRefundNo);
    }
}
