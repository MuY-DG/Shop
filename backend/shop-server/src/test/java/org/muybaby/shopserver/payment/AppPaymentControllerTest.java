package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.service.AppPaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppPaymentControllerTest extends PaymentTestSupport {

    @Autowired
    private AppPaymentService appPaymentService;

    @Autowired
    private PaymentProperties paymentProperties;

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
    void nonMockRepeatPayWithMissingSigningMaterialFailsClosedWithoutMockParams() throws Exception {
        seedEnabledPaymentConfig("", "");
        AppLoginSession session = appLogin("payment-repeat-non-mock-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        ReflectionTestUtils.setField(appPaymentService, "paymentProperties", nonMockPaymentProperties());

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
    void paymentSyncFinalizesPaidProviderResultThroughSharedStateTransition() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-sync-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        mockWechatPayProvider.markOrderPaid(outTradeNo, 6980L, "wx-transaction-sync");

        mockMvc.perform(post("/app/orders/{orderId}/payment/sync", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.transactionId").value("wx-transaction-sync"));

        assertPaidOrderState(order, outTradeNo, "wx-transaction-sync");
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
        return new PaymentProperties(
                paymentProperties.enabled(),
                false,
                PaymentConfigSource.DB,
                paymentProperties.appId(),
                paymentProperties.mchId(),
                paymentProperties.merchantSerialNo(),
                paymentProperties.privateKeyPath(),
                paymentProperties.apiV3Key(),
                paymentProperties.notifyUrl(),
                paymentProperties.refundNotifyUrl(),
                PaymentVerifyMode.PUBLIC_KEY,
                paymentProperties.publicKeyId(),
                paymentProperties.publicKeyPath(),
                paymentProperties.expireMinutes(),
                paymentProperties.secretKey()
        );
    }
}
