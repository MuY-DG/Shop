package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayRequest;
import org.muybaby.shopserver.payment.provider.WechatRefundRequest;
import org.muybaby.shopserver.aftersale.service.RefundRecoveryService;
import org.muybaby.shopserver.payment.service.PaymentInitiationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.pay.notification-route.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentNotificationRouteIssuanceTest extends PaymentTestSupport {

    @Autowired
    private PaymentInitiationService paymentInitiationService;

    @Autowired
    private RefundRecoveryService refundRecoveryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private MockWechatPayProvider provider;

    @MockitoSpyBean
    private PaymentConfigResolver paymentConfigResolver;

    @Test
    void newPaymentPersistsOpaqueRouteAndUsesItForSuccessfulCallback() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("routed-payment-issuance-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        clearInvocations(provider);

        paymentInitiationService.initiate(session.userId(), order.orderId());

        PaymentRoute paymentRoute = jdbcClient.sql("""
                        select out_trade_no, notification_route_token
                        from payment_order
                        where order_id = :orderId
                        """)
                .param("orderId", order.orderId())
                .query((rs, rowNum) -> new PaymentRoute(
                        rs.getString("out_trade_no"),
                        rs.getString("notification_route_token")))
                .single();
        assertThat(paymentRoute.routeToken()).matches("[A-Za-z0-9_-]{32}");

        ArgumentCaptor<WechatJsapiPrepayRequest> requestCaptor =
                ArgumentCaptor.forClass(WechatJsapiPrepayRequest.class);
        verify(provider).createJsapiPrepay(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().notifyUrl()).isEqualTo(
                "https://pay.test/wxpay/pay/notify/r/" + paymentRoute.routeToken());

        String transactionId = "wx-routed-payment-transaction";
        mockMvc.perform(post("/wxpay/pay/notify/r/{routeToken}", paymentRoute.routeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Wechatpay-Timestamp", currentWechatpayTimestamp())
                        .header("Wechatpay-Nonce", "routed-payment-nonce")
                        .header("Wechatpay-Serial", "mock-serial")
                        .header("Wechatpay-Signature", "mock-valid-signature")
                        .content(payNotificationBody(
                                "notify-routed-payment",
                                paymentRoute.outTradeNo(),
                                transactionId,
                                6980L)))
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_order
                        where order_id = :orderId
                          and status = 'PAID'
                          and transaction_id = :transactionId
                        """)
                .param("orderId", order.orderId())
                .param("transactionId", transactionId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_trade_no = :outTradeNo
                          and route_mode = 'ROUTED'
                          and route_digest <> ''
                          and status = 'SUCCESS'
                        """)
                .param("outTradeNo", paymentRoute.outTradeNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void newRefundPersistsItsOwnOpaqueRouteAndSendsRoutedNotifyUrl() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("routed-refund-issuance-user");
        SeedPaidOrder order = seedPaidOrder(
                session, 6980L, "PAID", "wx-routed-refund-transaction");
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());
        String applicationResponse = mockMvc.perform(
                        post("/app/orders/{orderId}/after-sales", order.orderId())
                                .header("Authorization", "Bearer " + session.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"afterSaleType":"REFUND_ONLY","reason":"路由退款测试",
                                         "requestedAmountCent":6980,"description":"route issuance",
                                         "evidenceFileIds":[%d]}
                                        """.formatted(evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(applicationResponse)
                .path("data").path("id").asLong();
        clearInvocations(provider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意路由退款测试"}
                                """))
                .andExpect(status().isOk());

        RefundRoute refundRoute = jdbcClient.sql("""
                        select out_refund_no, notification_route_token
                        from refund_order
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundRoute(
                        rs.getString("out_refund_no"),
                        rs.getString("notification_route_token")))
                .single();
        assertThat(refundRoute.routeToken()).matches("[A-Za-z0-9_-]{32}");

        ArgumentCaptor<WechatRefundRequest> requestCaptor =
                ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(provider).requestRefund(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().outRefundNo()).isEqualTo(refundRoute.outRefundNo());
        assertThat(requestCaptor.getValue().notifyUrl()).isEqualTo(
                "https://pay.test/wxpay/refund/notify/r/" + refundRoute.routeToken());

        mockMvc.perform(post("/wxpay/refund/notify/r/{routeToken}", refundRoute.routeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Wechatpay-Timestamp", currentWechatpayTimestamp())
                        .header("Wechatpay-Nonce", "routed-refund-nonce")
                        .header("Wechatpay-Serial", "mock-serial")
                        .header("Wechatpay-Signature", "mock-valid-signature")
                        .content(refundNotificationBody(
                                "notify-routed-refund",
                                order.outTradeNo(),
                                refundRoute.outRefundNo(),
                                "wx-routed-refund-success",
                                6980L)))
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", refundRoute.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("SUCCESS");
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where out_refund_no = :outRefundNo
                          and route_mode = 'ROUTED'
                          and route_digest <> ''
                          and status = 'SUCCESS'
                        """)
                .param("outRefundNo", refundRoute.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void invalidRoutedPaymentUrlDoesNotPersistPaymentOrChangeOrderState() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("routed-payment-url-preflight-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        jdbcClient.sql("update payment_config set notify_url = :notifyUrl where id = 91001")
                .param("notifyUrl", tooLongRoutedBaseUrl())
                .update();

        assertThatThrownBy(() -> paymentInitiationService.initiate(
                session.userId(), order.orderId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CREATED");
        assertThat(jdbcClient.sql("select count(*) from payment_order where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void invalidRoutedRefundUrlRollsBackRefundPreparation() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("routed-refund-url-preflight-user");
        SeedPaidOrder order = seedPaidOrder(
                session, 6980L, "PAID", "wx-routed-refund-preflight");
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());
        String applicationResponse = mockMvc.perform(
                        post("/app/orders/{orderId}/after-sales", order.orderId())
                                .header("Authorization", "Bearer " + session.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"afterSaleType":"REFUND_ONLY","reason":"退款地址预检",
                                         "requestedAmountCent":6980,"description":"preflight",
                                         "evidenceFileIds":[%d]}
                                        """.formatted(evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(applicationResponse)
                .path("data").path("id").asLong();
        jdbcClient.sql("update payment_config set refund_notify_url = :notifyUrl where id = 91001")
                .param("notifyUrl", tooLongRoutedBaseUrl())
                .update();
        clearInvocations(provider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"地址预检应回滚"}
                                """))
                .andExpect(status().isBadRequest());

        verify(provider, never()).requestRefund(any(), any());
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REQUESTED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("select count(*) from refund_order where after_sale_id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void sameRefundRecoveryReusesPersistedRouteTokenAndNotifyUrl() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("routed-refund-recovery-user");
        SeedPaidOrder order = seedPaidOrder(
                session, 6980L, "PAID", "wx-routed-refund-recovery");
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());
        String applicationResponse = mockMvc.perform(
                        post("/app/orders/{orderId}/after-sales", order.orderId())
                                .header("Authorization", "Bearer " + session.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"afterSaleType":"REFUND_ONLY","reason":"路由恢复测试",
                                         "requestedAmountCent":6980,"description":"recovery",
                                         "evidenceFileIds":[%d]}
                                        """.formatted(evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(applicationResponse)
                .path("data").path("id").asLong();
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意恢复测试"}
                                """))
                .andExpect(status().isOk());
        RefundRecoveryRoute route = jdbcClient.sql("""
                        select id, out_refund_no, notification_route_token
                        from refund_order
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundRecoveryRoute(
                        rs.getLong("id"),
                        rs.getString("out_refund_no"),
                        rs.getString("notification_route_token")))
                .single();
        provider.forgetRefund(route.outRefundNo());
        clearInvocations(provider);

        RefundRecoveryService.ManualRecoveryResult result =
                refundRecoveryService.resubmitRefundNow(route.refundOrderId());

        assertThat(result.resubmitted()).isTrue();
        ArgumentCaptor<WechatRefundRequest> requestCaptor =
                ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(provider).requestRefund(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().outRefundNo()).isEqualTo(route.outRefundNo());
        assertThat(requestCaptor.getValue().notifyUrl()).isEqualTo(
                "https://pay.test/wxpay/refund/notify/r/" + route.routeToken());
        assertThat(jdbcClient.sql("""
                        select notification_route_token
                        from refund_order
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", route.refundOrderId())
                .query(String.class)
                .single()).isEqualTo(route.routeToken());
    }

    @Test
    void closedRefundRetryGetsANewRouteToken() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("routed-closed-refund-retry-user");
        SeedPaidOrder order = seedPaidOrder(
                session, 6980L, "PAID", "wx-routed-closed-refund-retry");
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());
        String applicationResponse = mockMvc.perform(
                        post("/app/orders/{orderId}/after-sales", order.orderId())
                                .header("Authorization", "Bearer " + session.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"afterSaleType":"REFUND_ONLY","reason":"关闭后新单重试",
                                         "requestedAmountCent":6980,"description":"closed retry",
                                         "evidenceFileIds":[%d]}
                                        """.formatted(evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(applicationResponse)
                .path("data").path("id").asLong();
        String adminToken = adminLogin();
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"首次退款"}
                                """))
                .andExpect(status().isOk());
        RefundRoute original = jdbcClient.sql("""
                        select out_refund_no, notification_route_token
                        from refund_order
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundRoute(
                        rs.getString("out_refund_no"),
                        rs.getString("notification_route_token")))
                .single();
        jdbcClient.sql("""
                        update refund_order
                        set status = 'FAILED', callback_status = 'CLOSED',
                            failed_at = current_timestamp, updated_at = current_timestamp
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .update();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = 'REFUND_FAILED', updated_at = current_timestamp
                        where id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .update();
        clearInvocations(provider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"确认旧退款关闭，创建新退款单"}
                                """))
                .andExpect(status().isOk());

        List<RefundRoute> routes = jdbcClient.sql("""
                        select out_refund_no, notification_route_token
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundRoute(
                        rs.getString("out_refund_no"),
                        rs.getString("notification_route_token")))
                .list();
        assertThat(routes).hasSize(2);
        assertThat(routes.get(1).outRefundNo()).isNotEqualTo(original.outRefundNo());
        assertThat(routes.get(1).routeToken())
                .matches("[A-Za-z0-9_-]{32}")
                .isNotEqualTo(original.routeToken());
        ArgumentCaptor<WechatRefundRequest> requestCaptor =
                ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(provider).requestRefund(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().notifyUrl()).isEqualTo(
                "https://pay.test/wxpay/refund/notify/r/" + routes.get(1).routeToken());
    }

    @Test
    void refundPreparationCommitsBeforeAJoinedCallerTransactionCanRollBack() throws Exception {
        seedEnabledPaymentConfig();
        RequestedAfterSale requested = requestedAfterSale("refund-caller-transaction-user");
        String adminToken = adminLogin();
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(status -> {
            try {
                mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", requested.afterSaleId())
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"approvedAmountCent":6980,"auditNote":"调用方事务回滚测试"}
                                        """))
                        .andExpect(status().isOk());
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            status.setRollbackOnly();
        });

        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", requested.afterSaleId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", requested.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select count(*) from refund_order where after_sale_id = :afterSaleId")
                .param("afterSaleId", requested.afterSaleId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void repeatedRefundApprovalRejectsBeforeResolvingPaymentSecrets() throws Exception {
        seedEnabledPaymentConfig();
        RequestedAfterSale requested = requestedAfterSale("refund-repeat-preflight-user");
        String adminToken = adminLogin();
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", requested.afterSaleId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"首次审批"}
                                """))
                .andExpect(status().isOk());
        clearInvocations(paymentConfigResolver);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", requested.afterSaleId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"重复审批"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentConfigResolver);
        assertThat(jdbcClient.sql("select count(*) from refund_order where after_sale_id = :afterSaleId")
                .param("afterSaleId", requested.afterSaleId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void invalidRefundAmountAndNonClosedRetryRejectBeforeResolvingPaymentSecrets() throws Exception {
        seedEnabledPaymentConfig();
        RequestedAfterSale requested = requestedAfterSale("refund-ineligible-secret-user");
        String adminToken = adminLogin();
        clearInvocations(paymentConfigResolver);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", requested.afterSaleId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6900,"auditNote":"金额不一致"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentConfigResolver);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", requested.afterSaleId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"正确审批"}
                                """))
                .andExpect(status().isOk());
        jdbcClient.sql("""
                        update refund_order
                        set status = 'FAILED', callback_status = 'ABNORMAL',
                            failed_at = current_timestamp, updated_at = current_timestamp
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", requested.afterSaleId())
                .update();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = 'REFUND_FAILED', updated_at = current_timestamp
                        where id = :afterSaleId
                        """)
                .param("afterSaleId", requested.afterSaleId())
                .update();
        clearInvocations(paymentConfigResolver);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", requested.afterSaleId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"非 CLOSED 退款不可新单重试"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentConfigResolver);
        assertThat(jdbcClient.sql("select count(*) from refund_order where after_sale_id = :afterSaleId")
                .param("afterSaleId", requested.afterSaleId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private RequestedAfterSale requestedAfterSale(String username) throws Exception {
        AppLoginSession session = appLogin(username);
        SeedPaidOrder order = seedPaidOrder(session, 6980L, "PAID", "wx-" + username);
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());
        String response = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSaleType":"REFUND_ONLY","reason":"退款事务边界测试",
                                 "requestedAmountCent":6980,"description":"transaction boundary",
                                 "evidenceFileIds":[%d]}
                                """.formatted(evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(response).path("data").path("id").asLong();
        return new RequestedAfterSale(afterSaleId, order.orderId());
    }

    private String tooLongRoutedBaseUrl() {
        String prefix = "https://pay.test/";
        return prefix + "x".repeat(221 - prefix.length());
    }

    private String payNotificationBody(
            String notifyId,
            String outTradeNo,
            String transactionId,
            long amountCent
    ) {
        return """
                {
                  "id":"%s",
                  "event_type":"TRANSACTION.SUCCESS",
                  "resource":{
                    "out_trade_no":"%s",
                    "transaction_id":"%s",
                    "trade_state":"SUCCESS",
                    "success_time":"2026-07-08T12:00:00+08:00",
                    "amount":{"total":%d,"payer_total":%d,"currency":"CNY"}
                  }
                }
                """.formatted(notifyId, outTradeNo, transactionId, amountCent, amountCent);
    }

    private String refundNotificationBody(
            String notifyId,
            String outTradeNo,
            String outRefundNo,
            String refundId,
            long amountCent
    ) {
        return """
                {
                  "id":"%s",
                  "event_type":"REFUND.SUCCESS",
                  "resource":{
                    "out_trade_no":"%s",
                    "out_refund_no":"%s",
                    "refund_id":"%s",
                    "refund_status":"SUCCESS",
                    "success_time":"2026-07-08T14:00:00+08:00",
                    "amount":{"refund":%d,"total":%d,"currency":"CNY"}
                  }
                }
                """.formatted(
                notifyId, outTradeNo, outRefundNo, refundId, amountCent, amountCent);
    }

    private record PaymentRoute(String outTradeNo, String routeToken) {
    }

    private record RefundRoute(String outRefundNo, String routeToken) {
    }

    private record RefundRecoveryRoute(long refundOrderId, String outRefundNo, String routeToken) {
    }

    private record RequestedAfterSale(long afterSaleId, long orderId) {
    }
}
