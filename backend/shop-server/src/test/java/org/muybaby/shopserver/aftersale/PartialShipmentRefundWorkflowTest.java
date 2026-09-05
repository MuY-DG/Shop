package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.service.RefundFinalizationService;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class PartialShipmentRefundWorkflowTest extends PaymentTestSupport {

    @Autowired
    private RefundFinalizationService refundFinalizationService;

    @Autowired
    private PaymentConfigResolver paymentConfigResolver;

    @Test
    void returnedQuantityMovesThroughShipmentInspectionRefundAndPartialRestock() throws Exception {
        seedEnabledPaymentConfig();
        jdbcClient.sql("update wechat_shipping_runtime_setting set upload_enabled=false,delivery_enabled=false,receipt_reconciliation_enabled=false where id=1").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        jdbcClient.sql("insert into wechat_delivery_company(delivery_id,delivery_name,enabled,synced_at) values ('SF','顺丰速运',true,current_timestamp)").update();
        AppLoginSession user = appLogin("return-refund-workflow-user");
        SeedPaidOrder order = seedPaidOrder(
                user, 6980L, "PAID", "return-refund-workflow-transaction");
        long orderItemId = jdbcClient.sql("select id from order_item where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Long.class)
                .single();
        String adminToken = adminLogin();
        mockMvc.perform(post("/admin/orders/{orderId}/ship", order.orderId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"logisticsType":1,"itemDesc":"Repro x1","expressCompanyCode":"SF",
                                 "trackingNo":"SF-REPRO-FIRST","items":[{"orderItemId":%d,"quantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isOk());

        String addressResponse = mockMvc.perform(post("/admin/after-sale-return-addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contactName":"售后仓",
                                  "contactPhone":"13800138000",
                                  "province":"广东省",
                                  "city":"深圳市",
                                  "district":"南山区",
                                  "detailAddress":"科技园 1 号",
                                  "enabled":true,
                                  "defaultAddress":true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long addressId = objectMapper.readTree(addressResponse).path("data").path("id").asLong();

        String applyResponse = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKey":"return-refund-one-unit",
                                  "afterSaleType":"RETURN_REFUND",
                                  "reason":"一件商品需要退货",
                                  "requestedAmountCent":3490,
                                  "items":[{"orderItemId":%d,"quantity":1}]
                                }
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        long afterSaleId = objectMapper.readTree(applyResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/admin/after-sales/{id}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "auditNote":"同意退货",
                                  "returnAddressId":%d,
                                  "items":[{"orderItemId":%d,"approvedQuantity":1}]
                                }
                                """.formatted(addressId, orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_RETURN"))
                .andExpect(jsonPath("$.data.returnInfo.contactName").value("售后仓"));

        mockMvc.perform(put("/app/after-sales/{id}/return-shipment", afterSaleId)
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deliveryCompanyCode":"SF",
                                  "deliveryCompanyName":"顺丰速运",
                                  "trackingNo":"SF-RETURN-0001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNING"));

        mockMvc.perform(post("/admin/after-sales/{id}/return-received", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"仓库已签收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_INSPECTION"));

        mockMvc.perform(post("/admin/after-sales/{id}/return-inspection", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision":"ACCEPT",
                                  "note":"验收通过",
                                  "items":[{"orderItemId":%d,"restockQuantity":1}]
                                }
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"));

        RefundIdentity refund = jdbcClient.sql("""
                        select ro.out_refund_no, po.out_trade_no,
                               po.payment_config_id, po.payment_config_fingerprint
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
                        where ro.after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundIdentity(
                        rs.getString("out_refund_no"), rs.getString("out_trade_no"),
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint")))
                .single();
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                refund.paymentConfigId(), refund.paymentConfigFingerprint());
        assertThat(refundFinalizationService.apply(
                new RefundFinalizationService.ProviderRefundState(
                        refund.outRefundNo(), "wx-return-refund-1", refund.outTradeNo(),
                        "SUCCESS", 3490L, LocalDateTime.now(), "return-callback-digest"),
                config)).isEqualTo(RefundFinalizationService.Outcome.SUCCESS);

        assertThat(jdbcClient.sql("""
                        select concat(asr.status, '|', ret.inspection_result, '|',
                                      item.restock_quantity)
                        from after_sale_request asr
                        join after_sale_return ret on ret.after_sale_id = asr.id
                        join after_sale_item item on item.after_sale_id = asr.id
                        where asr.id = :id
                        """)
                .param("id", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDED|ACCEPTED|1");
        assertThat(jdbcClient.sql("""
                        select concat(status, '|', refund_status, '|', refunded_amount_cent)
                        from shop_order where id = :id
                        """)
                .param("id", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PARTIALLY_SHIPPED|PARTIALLY_REFUNDED|3490");
        assertThat(jdbcClient.sql("""
                        select concat(item.refunded_quantity, '|', stock.status, '|',
                                      stock.restocked_quantity)
                        from order_item item
                        join stock_lock stock on stock.order_item_id = item.id
                        where item.id = :id
                        """)
                .param("id", orderItemId)
                .query(String.class)
                .single()).isEqualTo("1|PARTIALLY_RESTOCKED|1");
        assertThat(jdbcClient.sql("select source_type from after_sale_fulfillment_allocation")
                .query(String.class).single()).isEqualTo("SHIPPED");
        mockMvc.perform(get("/admin/orders/{orderId}", order.orderId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingShipmentItems.length()").value(1))
                .andExpect(jsonPath("$.data.remainingShipmentItems[0].quantity").value(1));
        mockMvc.perform(post("/admin/orders/{orderId}/ship", order.orderId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"logisticsType":1,"itemDesc":"Repro remaining x1","expressCompanyCode":"SF",
                                 "trackingNo":"SF-REPRO-SECOND","items":[{"orderItemId":%d,"quantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isOk());
        assertThat(jdbcClient.sql("select status from shop_order where id=:id")
                .param("id", order.orderId()).query(String.class).single()).isEqualTo("SHIPPED");

    }

    private record RefundIdentity(
            String outRefundNo,
            String outTradeNo,
            Long paymentConfigId,
            String paymentConfigFingerprint
    ) {
    }
}
