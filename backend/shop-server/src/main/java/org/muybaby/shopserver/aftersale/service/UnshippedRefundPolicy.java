package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.order.repository.OrderItemFulfillmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Evaluated again under the order lock before automatic approval. */
@Service
public class UnshippedRefundPolicy {
    private final JdbcClient jdbcClient;
    private final OrderItemFulfillmentRepository fulfillment;

    public UnshippedRefundPolicy(JdbcClient jdbcClient, OrderItemFulfillmentRepository fulfillment) {
        this.jdbcClient = jdbcClient;
        this.fulfillment = fulfillment;
    }

    public boolean eligible(long afterSaleId) {
        var request = jdbcClient.sql("""
                        select r.order_id, r.after_sale_type, r.status, o.status as order_status
                        from after_sale_request r join shop_order o on o.id = r.order_id
                        where r.id = :id
                        """).param("id", afterSaleId)
                .query((rs, rowNum) -> new Request(rs.getLong("order_id"),
                        rs.getString("after_sale_type"), rs.getString("status"), rs.getString("order_status")))
                .optional().orElse(null);
        if (request == null || !"REFUND_ONLY".equals(request.type())
                || !"REQUESTED".equals(request.status())
                || !Set.of("PAID", "PARTIALLY_SHIPPED").contains(request.orderStatus())) return false;
        var items = fulfillment.items(request.orderId());
        var selected = jdbcClient.sql("""
                        select order_item_id, requested_quantity from after_sale_item where after_sale_id = :id
                        """).param("id", afterSaleId)
                .query((rs, rowNum) -> new Selected(rs.getLong("order_item_id"), rs.getInt("requested_quantity")))
                .list();
        return !selected.isEmpty() && selected.stream().allMatch(selection -> items.stream().anyMatch(item ->
                item.orderItemId() == selection.itemId() && selection.quantity() > 0
                        && item.unresolvedRefundedQuantity() == 0
                        && selection.quantity() <= item.remainingQuantity()));
    }

    private record Request(long orderId, String type, String status, String orderStatus) { }
    private record Selected(long itemId, int quantity) { }
}
