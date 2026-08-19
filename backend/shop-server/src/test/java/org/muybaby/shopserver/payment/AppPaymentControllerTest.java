package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.service.PaymentInitiationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppPaymentControllerTest extends PaymentTestSupport {

    @Autowired
    private PaymentInitiationService paymentInitiationService;

    @Autowired
    private PaymentProperties paymentProperties;

    @MockitoSpyBean
    private MockWechatPayProvider retryableMockWechatPayProvider;

    @Test
    void paymentEndpointsRequireAppToken() throws Exception {
        mockMvc.perform(post("/app/orders/{orderId}/pay", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/app/orders/{orderId}/cancel", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/app/orders/{orderId}/payment/sync", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotPayAnotherUsersOrder() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession owner = appLogin("payment-owner");
        AppLoginSession other = appLogin("payment-other");
        SeedOrder order = seedCreatedOrder(owner.userId(), 6980L, true);

        mockMvc.perform(post("/app/orders/{orderId}/pay", order.orderId())
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CREATED");
    }

    @Test
    void payCreatedOrderCreatesOneActivePaymentAndRepeatReusesItWithoutMarkingPaid() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-pay-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);

        String firstResponse = pay(session.token(), order.orderId());
        JsonNode firstData = objectMapper.readTree(firstResponse).path("data");
        String firstPackage = firstData.path("package").asText();

        assertThat(firstData.path("timeStamp").asText()).isNotBlank();
        assertThat(firstData.path("nonceStr").asText()).startsWith("mock-nonce-");
        assertThat(firstPackage).startsWith("prepay_id=mock-prepay-");
        assertThat(firstData.path("signType").asText()).isEqualTo("RSA");
        assertThat(firstData.path("paySign").asText()).startsWith("mock-pay-sign-");

        String outTradeNo = activeOutTradeNo(order.orderId());
        assertPaymentAttemptStatus(outTradeNo, "PREPAY_SUCCEEDED", true);
        assertThat(firstPackage).isEqualTo("prepay_id=mock-prepay-" + outTradeNo);
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAYING");
        assertThat(jdbcClient.sql("select count(*) from payment_order where order_id = :orderId and status = 'PAYING'")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select payer_openid from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(String.class)
                .single()).isEqualTo(session.openid());
        assertThat(jdbcClient.sql("select paid_at from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .optional()).isEmpty();
        mockMvc.perform(get("/app/orders/{orderId}", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.paymentRemainingSeconds").value(greaterThanOrEqualTo(23 * 60 * 60)))
                .andExpect(jsonPath("$.data.paymentRemainingSeconds").value(lessThanOrEqualTo(24 * 60 * 60)));
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_status_log
                        where order_id = :orderId
                          and event_type = 'PAYMENT_STARTED'
                          and from_status = 'CREATED'
                          and to_status = 'PAYING'
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);

        String repeatResponse = pay(session.token(), order.orderId());
        JsonNode repeatData = objectMapper.readTree(repeatResponse).path("data");

        assertThat(repeatData.path("package").asText()).isEqualTo(firstPackage);
        assertThat(jdbcClient.sql("select count(*) from payment_order where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_status_log
                        where order_id = :orderId and event_type = 'PAYMENT_STARTED'
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void failedPrepayRetryAppendsAttemptAndPaidSyncUpdatesOnlyTheBoundAttempt() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-attempt-retry-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        doThrow(new BusinessException(ErrorCode.ORDER_STATE_CONFLICT))
                .doCallRealMethod()
                .when(retryableMockWechatPayProvider)
                .createJsapiPrepay(any(), any());

        mockMvc.perform(post("/app/orders/{orderId}/pay", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        assertThat(jdbcClient.sql("select count(*) from payment_attempt where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(2);
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
        assertPaymentAttemptStatus(outTradeNo, "PREPAY_SUCCEEDED", true);

        mockWechatPayProvider.markOrderPaid(outTradeNo, 6980L, "wx-transaction-attempt-retry");
        mockMvc.perform(post("/app/orders/{orderId}/payment/sync", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

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
    }

    @Test
    void nonMockRepeatPayWithMissingSigningMaterialFailsClosedWithoutMockParams() throws Exception {
        seedEnabledPaymentConfig("", "");
        AppLoginSession session = appLogin("payment-repeat-non-mock-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        ReflectionTestUtils.setField(paymentInitiationService, "paymentProperties", nonMockPaymentProperties());

        mockMvc.perform(post("/app/orders/{orderId}/pay", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        String outTradeNo = activeOutTradeNo(order.orderId());
        assertThat(jdbcClient.sql("select count(*) from payment_order where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(String.class)
                .single()).isEqualTo("PAYING");
    }

    @Test
    void paymentSyncReconcilesExpiredPaidResultWithOriginalPaymentConfiguration() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-sync-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        jdbcClient.sql("""
                        update payment_order
                        set expires_at = timestamp '2026-07-07 09:00:00'
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
                .update();
        switchToClonedPaymentConfig(91002L);
        mockWechatPayProvider.markOrderPaid(outTradeNo, 6980L, "wx-transaction-sync");

        mockMvc.perform(post("/app/orders/{orderId}/payment/sync", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.transactionId").value("wx-transaction-sync"));

        assertPaidOrderState(order, outTradeNo, "wx-transaction-sync");
        assertPaymentAttemptStatus(outTradeNo, "PAID", true);
        assertThat(mockWechatPayProvider.queriedPaymentConfigId(outTradeNo)).isEqualTo(91001L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentSyncRejectsPaidProviderResultForDifferentOutTradeNo() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-sync-mismatch-user");
        SeedOrder firstOrder = seedCreatedOrder(session.userId(), 6980L, true);
        SeedOrder secondOrder = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), firstOrder.orderId());
        pay(session.token(), secondOrder.orderId());
        String firstOutTradeNo = activeOutTradeNo(firstOrder.orderId());
        String secondOutTradeNo = activeOutTradeNo(secondOrder.orderId());
        Map<String, WechatPayOrderQueryResult> paidOrders =
                (Map<String, WechatPayOrderQueryResult>) ReflectionTestUtils.getField(mockWechatPayProvider, "paidOrders");
        paidOrders.put(firstOutTradeNo, new WechatPayOrderQueryResult(
                true,
                secondOutTradeNo,
                "wx-transaction-wrong-sync",
                6980L,
                LocalDateTime.of(2026, 7, 8, 12, 0),
                "SUCCESS"
        ));

        mockMvc.perform(post("/app/orders/{orderId}/payment/sync", firstOrder.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertPayingPaymentState(firstOrder, firstOutTradeNo);
        assertPayingPaymentState(secondOrder, secondOutTradeNo);
    }

    @Test
    void cancelPayingOrderClosesProviderPaymentAndReleasesLocks() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-cancel-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());

        mockMvc.perform(post("/app/orders/{orderId}/cancel", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
        assertClosedReleasedState(order, outTradeNo);
        assertPaymentAttemptStatus(outTradeNo, "CLOSED", true);
    }

    private void assertPaymentAttemptStatus(String outTradeNo, String status, boolean paymentOrderBound) {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt
                        where out_trade_no = :outTradeNo
                          and status = :status
                          and (:paymentOrderBound = false or payment_order_id is not null)
                        """)
                .param("outTradeNo", outTradeNo)
                .param("status", status)
                .param("paymentOrderBound", paymentOrderBound)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private void assertPaidOrderState(SeedOrder order, String outTradeNo, String transactionId) {
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
        assertThat(jdbcClient.sql("select status from user_coupon where id = :userCouponId")
                .param("userCouponId", order.userCouponId())
                .query(String.class)
                .single()).isEqualTo("USED");
    }

    private void assertPayingPaymentState(SeedOrder order, String outTradeNo) {
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
                          and coalesce(payment_transaction_id, '') = ''
                          and merchant_trade_no = :outTradeNo
                        """)
                .param("orderId", order.orderId())
                .param("outTradeNo", outTradeNo)
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

    private void assertClosedReleasedState(SeedOrder order, String outTradeNo) {
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("select status from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("RELEASED");
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", order.skuId())
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("select status from user_coupon where id = :userCouponId")
                .param("userCouponId", order.userCouponId())
                .query(String.class)
                .single()).isEqualTo("CLAIMED");
    }

    private PaymentProperties nonMockPaymentProperties() {
        return new PaymentProperties(false, paymentProperties.expireMinutes());
    }
}
