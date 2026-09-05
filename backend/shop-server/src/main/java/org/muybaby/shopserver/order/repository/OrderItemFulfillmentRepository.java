package org.muybaby.shopserver.order.repository;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Shipment quantities remain historical facts, including units later returned. */
@Repository
public class OrderItemFulfillmentRepository {
    private final JdbcClient jdbcClient;

    public OrderItemFulfillmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Item> items(long orderId) {
        return jdbcClient.sql("""
                        select i.id, i.product_title, i.product_subtitle, i.main_image,
                               i.sku_image, i.display_image, i.spec_text, i.quantity,
                               i.refunded_quantity,
                               coalesce(s.shipped_quantity, 0) as shipped_quantity,
                               coalesce(a.cancelled_quantity, 0) as cancelled_quantity,
                               coalesce(a.allocated_quantity, 0) as allocated_quantity,
                               coalesce(a.unknown_quantity, 0) as unknown_quantity
                        from order_item i
                        left join (
                            select si.order_item_id, sum(si.quantity) as shipped_quantity
                            from order_shipment_item si
                            join order_item oi on oi.id=si.order_item_id
                            where oi.order_id=:orderId group by si.order_item_id
                        ) s on s.order_item_id = i.id
                        left join (
                            select ai.order_item_id, sum(a.quantity) as allocated_quantity,
                                   sum(case when a.source_type = 'UNSHIPPED' then a.quantity else 0 end)
                                       as cancelled_quantity,
                                   sum(case when a.source_type = 'LEGACY_UNKNOWN' then a.quantity else 0 end)
                                       as unknown_quantity
                            from after_sale_fulfillment_allocation a
                            join after_sale_item ai on ai.id = a.after_sale_item_id
                            join after_sale_request r on r.id = ai.after_sale_id
                            where r.status = 'REFUNDED' and r.order_id=:orderId
                            group by ai.order_item_id
                        ) a on a.order_item_id = i.id
                        where i.order_id = :orderId
                        order by i.id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Item(
                        rs.getLong("id"), rs.getString("product_title"), rs.getString("product_subtitle"),
                        rs.getString("main_image"), rs.getString("sku_image"), rs.getString("display_image"),
                        rs.getString("spec_text"), rs.getInt("quantity"), rs.getInt("shipped_quantity"),
                        rs.getInt("cancelled_quantity"),
                        rs.getInt("unknown_quantity") + Math.abs(rs.getInt("refunded_quantity")
                                - rs.getInt("allocated_quantity"))))
                .list();
    }

    /** Quantities whose shipped source has not already been assigned to another accepted after-sale. */
    public java.util.Map<Long, Integer> returnableQuantities(long orderId, String orderStatus) {
        List<Item> states = items(orderId);
        boolean legacyFullyShipped = ("SHIPPED".equals(orderStatus) || "COMPLETED".equals(orderStatus))
                && states.stream().allMatch(item -> item.shippedQuantity() == 0);
        java.util.Map<Long, Item> byId = new java.util.HashMap<>();
        states.forEach(item -> byId.put(item.orderItemId(), item));
        java.util.Map<Long, Integer> result = new java.util.LinkedHashMap<>();
        jdbcClient.sql("""
                        select i.id, i.quantity - i.refunded_quantity as refundable_quantity,
                               coalesce((select sum(si.quantity) from order_shipment_item si
                                         where si.order_item_id = i.id), 0)
                               - coalesce((select sum(a.quantity)
                                           from after_sale_fulfillment_allocation a
                                           join after_sale_item ai on ai.id = a.after_sale_item_id
                                           join after_sale_request r on r.id = ai.after_sale_id
                                           where ai.order_item_id = i.id and a.source_type = 'SHIPPED'
                                             and r.status not in ('REJECTED', 'RETURN_REJECTED', 'CANCELLED')),
                                          0) as returnable_quantity
                        from order_item i where i.order_id = :orderId order by i.id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> {
                    long id = rs.getLong("id");
                    int refundable = Math.max(0, rs.getInt("refundable_quantity"));
                    int quantity = legacyFullyShipped ? refundable
                            : byId.get(id).unresolvedRefundedQuantity() > 0 ? 0
                            : Math.min(refundable, Math.max(0, rs.getInt("returnable_quantity")));
                    return java.util.Map.entry(id, quantity);
                }).list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    public void requireKnownForShipping(long orderId) {
        if (items(orderId).stream().anyMatch(item -> item.unresolvedRefundedQuantity() > 0
                || item.quantity() < item.shippedQuantity() + item.cancelledQuantity())) {
            throw new BusinessException(ErrorCode.ORDER_FULFILLMENT_UNRESOLVED);
        }
    }

    public boolean isFullyShipped(long orderId) {
        List<Item> items = items(orderId);
        return !items.isEmpty() && items.stream().allMatch(item ->
                item.unresolvedRefundedQuantity() == 0
                        && item.quantity() == item.shippedQuantity() + item.cancelledQuantity())
                && jdbcClient.sql("select count(*) from order_shipment where order_id = :id")
                .param("id", orderId).query(Long.class).single() > 0;
    }

    public record Item(long orderItemId, String title, String subtitle, String mainImage,
                       String skuImage, String displayImage, String specText, int quantity,
                       int shippedQuantity, int cancelledQuantity, int unresolvedRefundedQuantity) {
        public int remainingQuantity() {
            return unresolvedRefundedQuantity > 0 ? 0
                    : Math.max(0, quantity - shippedQuantity - cancelledQuantity);
        }
    }
}
