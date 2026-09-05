package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.muybaby.shopserver.aftersale.service.RefundFinalizationService;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.muybaby.shopserver.logistics.service.WechatShippingErrorSanitizer;
import org.muybaby.shopserver.logistics.service.WechatShippingRuntimeSettingService;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadCoordinator;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadStateStore;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundFulfillmentCompletionTest extends PaymentTestSupport {

    @Autowired
    private RefundFinalizationService finalizationService;
    @Autowired
    private WechatShippingUploadStateStore uploadStateStore;
    @Autowired
    private WechatShippingErrorSanitizer shippingErrorSanitizer;

    @ParameterizedTest
    @CsvSource({"2,1,SHIPPED,true", "3,1,PARTIALLY_SHIPPED,false", "2,2,REFUNDED,false"})
    void successfulRefundReevaluatesTheUnshippedRemainder(
            int purchasedQuantity, int refundQuantity, String expectedStatus, boolean completedShipment
    ) throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession user = appLogin("refund-completes-shipping-" + purchasedQuantity + "-" + refundQuantity);
        SeedPaidOrder order = seedPaidOrder(user, purchasedQuantity * 3490L, "PAID", "refund-shipping-" + System.nanoTime());
        long orderItemId = jdbcClient.sql("select id from order_item where order_id = :id")
                .param("id", order.orderId()).query(Long.class).single();
        jdbcClient.sql("update order_item set quantity = :quantity where id = :id")
                .param("quantity", purchasedQuantity).param("id", orderItemId).update();
        jdbcClient.sql("update stock_lock set quantity = :quantity where order_item_id = :id")
                .param("quantity", purchasedQuantity).param("id", orderItemId).update();
        String admin = adminLogin();
        String shipmentBody = mockMvc.perform(post("/admin/orders/{id}/ship", order.orderId())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"logisticsType":4,"itemDesc":"先交付一件", "items":[{"orderItemId":%d,"quantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finalShipment").value(false))
                .andReturn().getResponse().getContentAsString();
        long shipmentId = objectMapper.readTree(shipmentBody).path("data").path("shipmentId").asLong();
        LocalDateTime shippedAt = LocalDateTime.of(2026, 8, 20, 9, 0);
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_provider_mode = 'REAL', wechat_upload_status = 'UPLOADED',
                            wechat_uploaded_at = :shippedAt, shipped_at = :shippedAt
                        where id = :id
                        """)
                .param("shippedAt", shippedAt).param("id", shipmentId).update();

        String application = mockMvc.perform(post("/app/orders/{id}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestKey":"cancel-remainder","afterSaleType":"REFUND_ONLY",
                                 "reason":"取消剩余商品","requestedAmountCent":%d,
                                 "items":[{"orderItemId":%d,"quantity":%d}]}
                                """.formatted(refundQuantity * 3490L, orderItemId, refundQuantity)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long afterSaleId = objectMapper.readTree(application).path("data").path("id").asLong();
        mockMvc.perform(post("/admin/after-sales/{id}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auditNote":"同意退款", "items":[{"orderItemId":%d,"approvedQuantity":%d}]}
                                """.formatted(orderItemId, refundQuantity)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REFUNDING"));
        RefundIdentity refund = jdbcClient.sql("""
                        select ro.out_refund_no, po.payment_config_id, po.payment_config_fingerprint
                        from refund_order ro join payment_order po on po.id = ro.payment_order_id
                        where ro.after_sale_id = :id
                        """)
                .param("id", afterSaleId)
                .query((rs, rowNum) -> new RefundIdentity(rs.getString("out_refund_no"),
                        rs.getLong("payment_config_id"), rs.getString("payment_config_fingerprint")))
                .single();
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(refund.configId(), refund.fingerprint());
        var success = new RefundFinalizationService.ProviderRefundState(
                refund.outRefundNo(), "wx-completed-" + afterSaleId, order.outTradeNo(), "SUCCESS",
                refundQuantity * 3490L, LocalDateTime.now(ZoneOffset.UTC), "fulfillment-success");
        int stockBeforeRefund = availableStock(orderItemId);

        assertThat(finalizationService.apply(success, config)).isEqualTo(RefundFinalizationService.Outcome.SUCCESS);

        int expectedRestockQuantity = Math.min(refundQuantity, purchasedQuantity - 1);
        List<Allocation> expectedAllocations = new ArrayList<>();
        expectedAllocations.add(new Allocation("UNSHIPPED", 0, expectedRestockQuantity));
        if (refundQuantity > expectedRestockQuantity) {
            long shipmentItemId = jdbcClient.sql("""
                            select id from order_shipment_item where shipment_id = :shipmentId and order_item_id = :itemId
                            """).param("shipmentId", shipmentId).param("itemId", orderItemId)
                    .query(Long.class).single();
            expectedAllocations.add(new Allocation("SHIPPED", shipmentItemId,
                    refundQuantity - expectedRestockQuantity));
        }
        assertThat(jdbcClient.sql("""
                        select a.source_type, a.shipment_item_id, a.quantity
                        from after_sale_fulfillment_allocation a
                        join after_sale_item i on i.id = a.after_sale_item_id
                        where i.after_sale_id = :id
                        """).param("id", afterSaleId)
                .query((rs, rowNum) -> new Allocation(rs.getString("source_type"),
                        rs.getLong("shipment_item_id"), rs.getInt("quantity"))).list())
                .containsExactlyInAnyOrderElementsOf(expectedAllocations);
        assertThat(jdbcClient.sql("select restocked_quantity from stock_lock where order_item_id = :id")
                .param("id", orderItemId).query(Integer.class).single()).isEqualTo(expectedRestockQuantity);
        assertThat(availableStock(orderItemId)).isEqualTo(stockBeforeRefund + expectedRestockQuantity);
        assertThat(jdbcClient.sql("select quantity from refund_inventory_restock_item where order_item_id = :id")
                .param("id", orderItemId).query(Integer.class).list()).containsExactly(expectedRestockQuantity);
        assertThat(jdbcClient.sql("select status from shop_order where id = :id")
                .param("id", order.orderId()).query(String.class).single()).isEqualTo(expectedStatus);
        assertThat(jdbcClient.sql("select final_shipment from order_shipment where id = :id")
                .param("id", shipmentId).query(Boolean.class).single()).isEqualTo(completedShipment);
        if (completedShipment) {
            assertThat(jdbcClient.sql("select shipped_at from shop_order where id = :id")
                    .param("id", order.orderId()).query(LocalDateTime.class).single()).isEqualTo(shippedAt);
            assertThat(jdbcClient.sql("select wechat_upload_status from order_shipment where id = :id")
                    .param("id", shipmentId).query(String.class).single()).isEqualTo("PENDING");
            assertThat(jdbcClient.sql("""
                            select count(*) from order_status_log
                            where order_id = :id and from_status = 'PARTIALLY_SHIPPED' and to_status = 'SHIPPED'
                              and event_type = 'REFUND_SUCCEEDED'
                            """).param("id", order.orderId()).query(Long.class).single()).isEqualTo(1L);
        } else {
            assertThat(jdbcClient.sql("select wechat_upload_status from order_shipment where id = :id")
                    .param("id", shipmentId).query(String.class).single()).isEqualTo("UPLOADED");
        }
        var orderBeforeDuplicate = jdbcClient.sql("select * from shop_order where id = :id")
                .param("id", order.orderId()).query().singleRow();
        var shipmentBeforeDuplicate = jdbcClient.sql("select * from order_shipment where id = :id")
                .param("id", shipmentId).query().singleRow();
        var stockLockBeforeDuplicate = jdbcClient.sql("select * from stock_lock where order_item_id = :id")
                .param("id", orderItemId).query().singleRow();
        var restockLedgerBeforeDuplicate = jdbcClient.sql("""
                        select * from refund_inventory_restock_item where order_item_id = :id order by id
                        """).param("id", orderItemId).query().listOfRows();
        var stockLogsBeforeDuplicate = jdbcClient.sql("select * from stock_log where order_id = :id order by id")
                .param("id", order.orderId()).query().listOfRows();
        assertThat(finalizationService.apply(success, config)).isEqualTo(RefundFinalizationService.Outcome.DUPLICATE);
        assertThat(jdbcClient.sql("select * from shop_order where id = :id")
                .param("id", order.orderId()).query().singleRow()).isEqualTo(orderBeforeDuplicate);
        assertThat(jdbcClient.sql("select * from order_shipment where id = :id")
                .param("id", shipmentId).query().singleRow()).isEqualTo(shipmentBeforeDuplicate);
        assertThat(jdbcClient.sql("select * from stock_lock where order_item_id = :id")
                .param("id", orderItemId).query().singleRow()).isEqualTo(stockLockBeforeDuplicate);
        assertThat(jdbcClient.sql("select * from refund_inventory_restock_item where order_item_id = :id order by id")
                .param("id", orderItemId).query().listOfRows()).isEqualTo(restockLedgerBeforeDuplicate);
        assertThat(jdbcClient.sql("select * from stock_log where order_id = :id order by id")
                .param("id", order.orderId()).query().listOfRows()).isEqualTo(stockLogsBeforeDuplicate);
        assertThat(availableStock(orderItemId)).isEqualTo(stockBeforeRefund + expectedRestockQuantity);
        if (completedShipment) {
            mockMvc.perform(post("/app/orders/{id}/confirm-receipt", order.orderId())
                            .header("Authorization", "Bearer " + user.token()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
            var provider = mock(WechatShippingProvider.class);
            var runtime = mock(WechatShippingRuntimeSettingService.class);
            when(runtime.deliveryEnabledFailClosed()).thenReturn(true);
            when(provider.mode()).thenReturn(WechatProviderMode.REAL);
            when(provider.queryCapability()).thenReturn(WechatShippingCapabilityResult.available());
            when(provider.upload(any())).thenReturn(WechatShippingUploadResult.uploaded());
            var coordinator = new WechatShippingUploadCoordinator(
                    runtime, provider, uploadStateStore, shippingErrorSanitizer);

            assertThat(coordinator.deliverDue(50)).isEqualTo(1);

            var request = ArgumentCaptor.forClass(WechatShippingUploadRequest.class);
            verify(provider).upload(request.capture());
            assertThat(request.getValue().orderId()).isEqualTo(order.orderId());
            assertThat(request.getValue().allDelivered()).isTrue();
            assertThat(jdbcClient.sql("select wechat_upload_status from order_shipment where id = :id")
                    .param("id", shipmentId).query(String.class).single()).isEqualTo("UPLOADED");
            assertThat(jdbcClient.sql("select status from shop_order where id = :id")
                    .param("id", order.orderId()).query(String.class).single()).isEqualTo("COMPLETED");
        }
    }

    private int availableStock(long orderItemId) {
        return jdbcClient.sql("""
                        select s.stock_available from product_sku s join order_item i on i.sku_id = s.id
                        where i.id = :id
                        """).param("id", orderItemId).query(Integer.class).single();
    }

    private record Allocation(String source, long shipmentItemId, int quantity) {
    }

    private record RefundIdentity(String outRefundNo, long configId, String fingerprint) {
    }
}
