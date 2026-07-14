package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
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

    @Test
    void successfulRefundNotificationFinalizesRefundAndDuplicateNotificationIsIdempotent() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-success", 6980L, 3980L);
        String body = refundNotifyBody(
                "notify-refund-success",
                "REFUND.SUCCESS",
                approved.outTradeNo(),
                approved.outRefundNo(),
                "wx-refund-success",
                "SUCCESS",
                3980L,
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
    void failedNotificationAfterSuccessfulRefundIsIgnoredWithoutDowngradingState() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-success-then-failed", 6980L, 3980L);
        postRefundNotify(refundNotifyBody(
                        "notify-refund-success-before-late-failure",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-success-before-late-failure",
                        "SUCCESS",
                        3980L,
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
                        3980L,
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
        ApprovedRefund approved = approveRefund("after-sale-refund-failed", 6980L, 3980L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-failed",
                        "REFUND.ABNORMAL",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-failed",
                        "ABNORMAL",
                        3980L,
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
    void invalidRefundNotificationLogsFailedVerificationWithoutChangingRefundState() throws Exception {
        ApprovedRefund approved = approveRefund("after-sale-refund-invalid", 6980L, 3980L);

        postRefundNotify(refundNotifyBody(
                        "notify-refund-invalid",
                        "REFUND.SUCCESS",
                        approved.outTradeNo(),
                        approved.outRefundNo(),
                        "wx-refund-invalid",
                        "SUCCESS",
                        3980L,
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
                .single()).isEqualTo(1);
    }

    private ApprovedRefund approveRefund(String code, long paidAmountCent, long approvedAmountCent) throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin(code + "-app");
        SeedPaidOrder order = seedPaidOrder(appUser, paidAmountCent, "PAID", "wx-refund-" + code);
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
        return mockMvc.perform(post("/wxpay/refund/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", "1783500000")
                .header("Wechatpay-Nonce", "mock-refund-notify-nonce")
                .header("Wechatpay-Serial", "mock-refund-serial")
                .header("Wechatpay-Signature", signature)
                .content(body));
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
    }

    private record ApprovedRefund(long afterSaleId, long orderId, String outTradeNo, String outRefundNo) {
    }
}
