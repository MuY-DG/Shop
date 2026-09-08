package org.muybaby.shopserver.order;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.order.service.OrderItemAfterSaleQueryService;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderItemAfterSaleQueryTest extends PaymentTestSupport {
    @Autowired OrderItemAfterSaleQueryService summaries;

    @Test
    void separateProductsKeepCompletedRefundAndNewApplicationInBothOrderApis() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("item-refund-api");
        var order = seedPaidOrder(user, 2000, "PAID", "wx-item-refund-api");
        long firstItem = order.orderId() + 1;
        jdbcClient.sql("update order_item set quantity=1, line_amount_cent=1000, paid_amount_allocated_cent=1000 where id=:id")
                .param("id", firstItem).update();
        long secondItem = firstItem + 100;
        jdbcClient.sql("""
                insert into order_item (id, order_id, sku_id, spu_id, sku_code, product_title,
                                        quantity, unit_price_cent, line_amount_cent, paid_amount_allocated_cent)
                select :second, order_id, sku_id, spu_id, sku_code, '第二件商品', 1, 1000, 1000, 1000
                from order_item where id=:first
                """).param("second", secondItem).param("first", firstItem).update();
        sale(order, user.userId(), firstItem, 501, "REFUNDED", 1, 1000, true);
        sale(order, user.userId(), secondItem, 502, "REQUESTED", 1, 1000, false);
        jdbcClient.sql("update shop_order set refunded_amount_cent=1000, refund_status='PARTIALLY_REFUNDED' where id=:id")
                .param("id", order.orderId()).update();

        for (String api : new String[]{"/app/orders/" + order.orderId(), "/admin/orders/" + order.orderId()}) {
            mockMvc.perform(get(api).header("Authorization", "Bearer " + (api.startsWith("/app") ? user.token() : adminLogin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PAID"))
                    .andExpect(jsonPath("$.data.refundedAmountCent").value(1000))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                    .andExpect(jsonPath("$.data.items[0].afterSale.refundedAmountCent").value(1000))
                    .andExpect(jsonPath("$.data.items[0].afterSale.fullyRefunded").value(true))
                    .andExpect(jsonPath("$.data.items[1].afterSale.refundedAmountCent").value(0))
                    .andExpect(jsonPath("$.data.items[1].afterSale.records[0].status").value("REQUESTED"));
        }
        mockMvc.perform(get("/app/orders").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].refundedAmountCent").value(1000))
                .andExpect(jsonPath("$.data.records[0].items[0].afterSale.refundedQuantity").value(1))
                .andExpect(jsonPath("$.data.records[0].items[1].afterSale.records[0].afterSaleId").value(502));
        var other = appLogin("item-refund-other");
        mockMvc.perform(get("/app/orders/" + order.orderId()).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void multipleSuccessesAccumulateOnceAndHiddenHistoryStillContributes() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("item-refund-accumulate");
        var order = seedPaidOrder(user, 3000, "PAID", "wx-item-refund-accumulate");
        long item = order.orderId() + 1;
        jdbcClient.sql("update order_item set quantity=3, paid_amount_allocated_cent=3000 where id=:id").param("id", item).update();
        sale(order, user.userId(), item, 601, "REFUNDED", 1, 1000, true);
        sale(order, user.userId(), item, 602, "REFUNDED", 1, 1000, true);
        sale(order, user.userId(), item, 603, "REFUNDING", 1, 1000, false);
        refund(order, 601, "FAILED", 1000, "old-attempt");
        jdbcClient.sql("update after_sale_request set app_deleted_at=current_timestamp where id=601").update();
        var result = summaries.forOrders(java.util.List.of(order.orderId())).get(item);
        assertThat(result.refundedAmountCent()).isEqualTo(2000);
        assertThat(result.refundedQuantity()).isEqualTo(2);
        assertThat(result.fullyRefunded()).isFalse();
        assertThat(result.records()).extracting(record -> record.status()).containsExactly("REFUNDING", "REFUNDED", "REFUNDED");
        assertThat(result.records().getLast().appVisible()).isFalse();
        refund(order, 603, "SUCCESS", 1000, "last-refund");
        assertThat(summaries.forOrders(java.util.List.of(order.orderId())).get(item).fullyRefunded()).isTrue();
    }

    @Test
    void amountOnlyRefundAndRejectedOrUnsubmittedApplicationsNeverLookFullyRefunded() throws Exception {
        seedEnabledPaymentConfig();
        var user = appLogin("item-refund-amount");
        var order = seedPaidOrder(user, 2000, "COMPLETED", "wx-item-refund-amount");
        long item = order.orderId() + 1;
        jdbcClient.sql("update order_item set paid_amount_allocated_cent=2000 where id=:id").param("id", item).update();
        sale(order, user.userId(), item, 701, "REFUNDED", 2, 500, true);
        sale(order, user.userId(), item, 702, "REJECTED", 2, 500, false);
        var result = summaries.forOrders(java.util.List.of(order.orderId())).get(item);
        assertThat(result.refundedQuantity()).isEqualTo(2);
        assertThat(result.refundedAmountCent()).isEqualTo(500);
        assertThat(result.fullyRefunded()).isFalse();
        assertThat(result.records()).hasSize(1);
        var untouched = seedPaidOrder(user, 1000, "PAID", "wx-item-untouched");
        assertThat(summaries.forOrders(java.util.List.of(untouched.orderId()))).isEmpty();
        assertThat(summaries.forOrders(java.util.List.of())).isEmpty();
    }

    private void sale(SeedPaidOrder order, long user, long item, long id, String state, int quantity, long amount, boolean refunded) {
        jdbcClient.sql("""
                insert into after_sale_request(id, order_id, user_id, after_sale_no, after_sale_type, status,
                                               reason, requested_amount_cent)
                values (:id,:order,:user,:number,'REFUND_ONLY',:status,'测试退款',:amount)
                """).param("id", id).param("order", order.orderId()).param("user", user)
                .param("number", "AS-" + id).param("status", state).param("amount", amount).update();
        jdbcClient.sql("""
                insert into after_sale_item(after_sale_id,order_item_id,sku_id,order_quantity_snapshot,
                                            paid_amount_basis_cent,requested_quantity,approved_quantity,
                                            requested_amount_cent,approved_amount_cent)
                select :sale,id,sku_id,quantity,coalesce(paid_amount_allocated_cent,line_amount_cent),:quantity,
                       case when :status='REQUESTED' then null else :quantity end,:amount,
                       case when :status='REQUESTED' then null else :amount end
                from order_item where id=:item
                """).param("sale", id).param("quantity", quantity).param("status", state)
                .param("amount", amount).param("item", item).update();
        if (refunded) refund(order, id, "SUCCESS", amount, "refund-" + id);
    }

    private void refund(SeedPaidOrder order, long sale, String status, long amount, String number) {
        jdbcClient.sql("""
                insert into refund_order(after_sale_id,order_id,payment_order_id,out_refund_no,refund_amount_cent,
                                         status,requested_at,notification_route_token)
                select :sale,:order,id,:number,:amount,:status,current_timestamp,:route
                from payment_order where order_id=:order
                """).param("sale", sale).param("order", order.orderId()).param("number", number)
                .param("amount", amount).param("status", status)
                .param("route", java.util.UUID.randomUUID().toString().replace("-", "")).update();
    }
}
