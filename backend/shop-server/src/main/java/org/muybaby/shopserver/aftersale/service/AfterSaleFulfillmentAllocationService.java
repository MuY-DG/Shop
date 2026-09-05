package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.repository.OrderItemFulfillmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Called under the order lock at approval; allocations survive retries and callbacks. */
@Service
public class AfterSaleFulfillmentAllocationService {
    private final JdbcClient jdbcClient;
    private final OrderItemFulfillmentRepository fulfillment;

    public AfterSaleFulfillmentAllocationService(JdbcClient jdbcClient,
                                                OrderItemFulfillmentRepository fulfillment) {
        this.jdbcClient = jdbcClient;
        this.fulfillment = fulfillment;
    }

    public void allocate(long afterSaleId, AfterSaleV2WorkflowService.ApprovalPlan plan,
                         boolean returnRequired, LocalDateTime now) {
        var order = jdbcClient.sql("""
                        select o.id, o.status from shop_order o
                        join after_sale_request r on r.order_id = o.id where r.id = :id
                        """)
                .param("id", afterSaleId)
                .query((rs, rowNum) -> new Order(rs.getLong("id"), rs.getString("status"))).single();
        var items = fulfillment.items(order.id());
        boolean legacyShippedOrder = ("SHIPPED".equals(order.status()) || "COMPLETED".equals(order.status()))
                && jdbcClient.sql("select count(*) from order_shipment_item s join order_item i"
                                + " on i.id=s.order_item_id where i.order_id=:id")
                .param("id", order.id()).query(Long.class).single() == 0;
        // A request can be approved only once. Replacing its rows also handles migrated,
        // not-yet-approved legacy requests without reserving those quantities twice.
        jdbcClient.sql("""
                        delete from after_sale_fulfillment_allocation
                        where after_sale_item_id in (select id from after_sale_item where after_sale_id=:id)
                        """).param("id", afterSaleId).update();
        for (var approved : plan.items()) {
            var item = items.stream().filter(value -> value.orderItemId() == approved.orderItemId())
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
            if (!legacyShippedOrder && (item.unresolvedRefundedQuantity() > 0
                    || item.quantity() < item.shippedQuantity() + item.cancelledQuantity())) {
                throw new BusinessException(ErrorCode.ORDER_FULFILLMENT_UNRESOLVED);
            }
            int outstanding = approved.approvedQuantity();
            int unshipped = !returnRequired && !legacyShippedOrder
                    ? Math.min(outstanding, item.remainingQuantity()) : 0;
            if (unshipped > 0) {
                insert(approved.afterSaleItemId(), "UNSHIPPED", 0, unshipped, now);
                outstanding -= unshipped;
            }
            // For interchangeable units of one order line, parcel allocation is FIFO
            // accounting, not a claim that the customer identified a particular parcel.
            var parcels = jdbcClient.sql("""
                            select s.id, s.quantity - coalesce(a.allocated_quantity, 0) as available_quantity
                            from order_shipment_item s
                            left join (
                                select a.shipment_item_id, sum(a.quantity) as allocated_quantity
                                from after_sale_fulfillment_allocation a
                                join after_sale_item ai on ai.id=a.after_sale_item_id
                                join after_sale_request r on r.id=ai.after_sale_id
                                where a.source_type='SHIPPED' and ai.order_item_id=:id
                                  and r.status not in ('REJECTED','RETURN_REJECTED','CANCELLED')
                                group by a.shipment_item_id
                            ) a on a.shipment_item_id=s.id
                            where s.order_item_id=:id order by s.id
                            """).param("id", approved.orderItemId())
                    .query((rs, rowNum) -> new Parcel(rs.getLong("id"), rs.getInt("available_quantity"))).list();
            for (var parcel : parcels) {
                int quantity = Math.min(outstanding, Math.max(0, parcel.available()));
                if (quantity > 0) {
                    insert(approved.afterSaleItemId(), "SHIPPED", parcel.id(), quantity, now);
                    outstanding -= quantity;
                }
            }
            if (outstanding > 0) {
                if (!legacyShippedOrder) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
                }
                insert(approved.afterSaleItemId(), "LEGACY_UNKNOWN", 0, outstanding, now);
            }
            if (!returnRequired) {
                jdbcClient.sql("update after_sale_item set restock_quantity=:quantity where id=:id")
                        .param("quantity", unshipped).param("id", approved.afterSaleItemId()).update();
            }
        }
    }

    private void insert(long afterSaleItemId, String source, long shipmentItemId, int quantity,
                        LocalDateTime now) {
        jdbcClient.sql("""
                        insert into after_sale_fulfillment_allocation
                            (after_sale_item_id, source_type, shipment_item_id, quantity, created_at)
                        values (:id, :source, :shipmentItemId, :quantity, :now)
                        """)
                .param("id", afterSaleItemId).param("source", source).param("shipmentItemId", shipmentItemId)
                .param("quantity", quantity).param("now", now).update();
    }

    private record Order(long id, String status) { }
    private record Parcel(long id, int available) { }
}
