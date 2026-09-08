package org.muybaby.shopserver.aftersale;

import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.service.UnshippedRefundScheduler;
import org.muybaby.shopserver.aftersale.service.RefundFinalizationService;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class UnshippedAutomaticRefundTest extends PaymentTestSupport {
    @Autowired UnshippedRefundScheduler scheduler;
    @Autowired RefundFinalizationService finalization;
    @MockitoSpyBean MockWechatPayProvider provider;

    @Test
    void applicationQueuesOnceAndRefundRequiresConfirmedSuccess() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-full");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-full");
        long id = apply(user, order, 2, "full");
        assertThat(apply(user, order, 2, "full")).isEqualTo(id);
        assertThat(count("select count(*) from after_sale_status_log where event_type='AUTO_REFUND_QUEUED'")).isEqualTo(1);
        scheduler.runOnce();
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REFUNDING");
        verify(provider, times(1)).requestRefund(any(), any());
        assertThat(count("select count(*) from after_sale_status_log where event_type='REFUND_STARTED' and operator_type='SYSTEM' and operator_id is null")).isEqualTo(1);
        assertThat(count("select count(*) from refund_provider_attempt where source='SYSTEM'")).isGreaterThan(0);
        var refundNo = jdbcClient.sql("select out_refund_no from refund_order where after_sale_id=:id")
                .param("id", id).query(String.class).single();
        finalization.apply(new RefundFinalizationService.ProviderRefundState(refundNo, "wx-auto-result",
                order.outTradeNo(), "SUCCESS", 2000, LocalDateTime.now(), "test"), paymentConfigResolver.resolve());
        assertThat(state(id)).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("select status from shop_order where id=:id").param("id", order.orderId()).query(String.class).single()).isEqualTo("REFUNDED");
        assertThat(count("select count(*) from refund_order")).isEqualTo(1);
    }

    @Test
    void partialShipmentOnlyAutomaticallyRefundsRemainingUnshippedQuantity() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-partial");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-partial");
        ship(order, 1);
        long id = apply(user, order, 1, "remaining");
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select source_type from after_sale_fulfillment_allocation").query(String.class).single()).isEqualTo("UNSHIPPED");
        assertThat(jdbcClient.sql("select refund_amount_cent from refund_order").query(Long.class).single()).isEqualTo(1000);
    }

    @Test
    void mixedShippedAndUnshippedApplicationRemainsForMerchantReview() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-mixed");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-mixed");
        ship(order, 1);
        long id = apply(user, order, 2, "mixed");
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REQUESTED");
        assertThat(count("select count(*) from after_sale_status_log where event_type='AUTO_REFUND_QUEUED'")).isZero();
        verify(provider, never()).requestRefund(any(), any());
    }

    @Test
    void changedFulfillmentDuringProviderPreflightIsRecheckedBeforeRefund() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-race");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-race");
        long id = apply(user, order, 2, "race");
        doAnswer(invocation -> {
            jdbcClient.sql("update shop_order set status='SHIPPED' where id=:id").param("id", order.orderId()).update();
            return invocation.callRealMethod();
        }).when(provider).queryOrder(any(), any());
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REQUESTED");
        assertThat(count("select count(*) from refund_order")).isZero();
        assertThat(count("select count(*) from after_sale_status_log where event_type='AUTO_REFUND_REVIEW_REQUIRED'")).isEqualTo(1);
        verify(provider, never()).requestRefund(any(), any());
    }

    @Test
    void insufficientBalanceNeverReportsSuccessOrRepeatedlySubmits() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-failed");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-failed");
        long id = apply(user, order, 2, "failed");
        doThrow(new ServiceException(mock(HttpRequest.class), 403,
                "{\"code\":\"NOT_ENOUGH\",\"message\":\"test\"}"))
                .when(provider).requestRefund(any(), any());
        scheduler.runOnce();
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REFUND_FAILED");
        verify(provider, times(1)).requestRefund(any(), any());
    }

    @Test
    void concurrentWorkersSubmitOnlyOneRefund() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-concurrent");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-concurrent");
        long id = apply(user, order, 2, "concurrent");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(provider).queryOrder(any(), any());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(scheduler::runOnce);
            var second = workers.submit(scheduler::runOnce);
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(state(id)).isEqualTo("REFUNDING");
        assertThat(count("select count(*) from refund_order")).isEqualTo(1);
        verify(provider, times(1)).requestRefund(any(), any());
    }

    @Test
    void preflightFailureLeavesOneAuditedMerchantReviewRequest() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-preflight");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-preflight");
        long id = apply(user, order, 2, "preflight");
        mockWechatPayProvider.markOrderState(order.outTradeNo(), "NOTPAY");
        scheduler.runOnce();
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REQUESTED");
        assertThat(count("select count(*) from after_sale_status_log where event_type='AUTO_REFUND_REVIEW_REQUIRED'")).isEqualTo(1);
        verify(provider, never()).requestRefund(any(), any());
        verify(provider, times(1)).queryOrder(any(), any());
    }

    @Test
    void oldPendingRequestsWithoutQueueMarkerAreNotAutomaticallyProcessed() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-historical");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-historical");
        long id = apply(user, order, 2, "old");
        jdbcClient.sql("delete from after_sale_status_log where event_type='AUTO_REFUND_QUEUED'").update();
        scheduler.runOnce();
        assertThat(state(id)).isEqualTo("REQUESTED");
        verify(provider, never()).requestRefund(any(), any());
    }

    @Test
    void orderListReturnsTrackingSnapshotAndOmitsItAfterFullRefund() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("auto-list");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-auto-list");
        ship(order, 2);
        long shipmentId = jdbcClient.sql("select id from order_shipment where order_id=:id")
                .param("id", order.orderId()).query(Long.class).single();
        jdbcClient.sql("insert into shipment_tracking_snapshot(shipment_id, logistics_status) values (:id,2)")
                .param("id", shipmentId).update();
        mockMvc.perform(get("/app/orders").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].logisticsSummary.statusText").value("运输中"))
                .andExpect(jsonPath("$.data.records[0].logisticsSummary.packageCount").value(1));
        jdbcClient.sql("update shop_order set status='REFUNDED' where id=:id").param("id", order.orderId()).update();
        mockMvc.perform(get("/app/orders").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].logisticsSummary").doesNotExist());
    }

    private long apply(AppLoginSession user, SeedPaidOrder order, int quantity, String key) throws Exception {
        long itemId = jdbcClient.sql("select id from order_item where order_id=:id").param("id", order.orderId()).query(Long.class).single();
        String response = mockMvc.perform(post("/app/orders/{id}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + user.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"%s","afterSaleType":"REFUND_ONLY","reason":"不想要了",
                                 "items":[{"orderItemId":%d,"quantity":%d}]}
                                """.formatted(key, itemId, quantity)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private void ship(SeedPaidOrder order, int quantity) throws Exception {
        jdbcClient.sql("update wechat_shipping_runtime_setting set upload_enabled=false,delivery_enabled=false,receipt_reconciliation_enabled=false where id=1").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        jdbcClient.sql("insert into wechat_delivery_company(delivery_id,delivery_name,enabled,synced_at) values ('SF','顺丰速运',true,current_timestamp)").update();
        long itemId = jdbcClient.sql("select id from order_item where order_id=:id").param("id", order.orderId()).query(Long.class).single();
        mockMvc.perform(post("/admin/orders/{id}/ship", order.orderId())
                        .header("Authorization", "Bearer " + adminLogin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"logisticsType":1,"itemDesc":"测试商品","expressCompanyCode":"SF",
                                 "trackingNo":"SF-TEST","items":[{"orderItemId":%d,"quantity":%d}]}
                                """.formatted(itemId, quantity))).andExpect(status().isOk());
    }

    private String state(long id) {
        return jdbcClient.sql("select status from after_sale_request where id=:id").param("id", id).query(String.class).single();
    }
    private long count(String sql) { return jdbcClient.sql(sql).query(Long.class).single(); }
}
