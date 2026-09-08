package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.order.dto.OrderItemAfterSaleResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderItemAfterSaleQueryService {
    private final JdbcClient jdbcClient;

    public OrderItemAfterSaleQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Call only after the caller has authorized access to these orders. One query per page. */
    public Map<Long, OrderItemAfterSaleResponse> forOrders(List<Long> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        var rows = jdbcClient.sql("""
                        select oi.id as item_id, oi.quantity as purchased_quantity,
                               coalesce(oi.paid_amount_allocated_cent, ai.paid_amount_basis_cent) as paid_basis,
                               r.id as after_sale_id, r.after_sale_no, r.status, r.app_deleted_at,
                               coalesce(ai.approved_quantity, ai.requested_quantity) as quantity,
                               coalesce(ai.approved_amount_cent, ai.requested_amount_cent) as amount_cent,
                               exists (select 1 from refund_order ro
                                       where ro.after_sale_id = r.id and ro.status = 'SUCCESS') as refunded
                        from order_item oi
                        join after_sale_item ai on ai.order_item_id = oi.id
                        join after_sale_request r on r.id = ai.after_sale_id and r.order_id = oi.order_id
                        where oi.order_id in (:ids)
                          and coalesce(ai.approved_quantity, ai.requested_quantity) > 0
                          and (r.status in ('REQUESTED','APPROVED','WAITING_RETURN','RETURNING',
                                            'WAITING_INSPECTION','REFUNDING','REFUND_FAILED')
                               or exists (select 1 from refund_order ro
                                          where ro.after_sale_id = r.id and ro.status = 'SUCCESS'))
                        order by r.id desc, ai.id
                        """).param("ids", orderIds)
                .query((rs, rowNum) -> new Row(rs.getLong("item_id"), rs.getInt("purchased_quantity"),
                        rs.getLong("paid_basis"), rs.getBoolean("refunded"),
                        new OrderItemAfterSaleResponse.Record(rs.getLong("after_sale_id"),
                                rs.getString("after_sale_no"), rs.getBoolean("refunded") ? "REFUNDED" : rs.getString("status"),
                                rs.getInt("quantity"), rs.getLong("amount_cent"), rs.getObject("app_deleted_at") == null)))
                .list();
        Map<Long, Accumulator> grouped = new HashMap<>();
        for (var row : rows) {
            var item = grouped.computeIfAbsent(row.itemId(), ignored -> new Accumulator(row.quantity(), row.paidBasis()));
            item.records.add(row.record());
            if (row.refunded()) {
                item.refundedQuantity = Math.addExact(item.refundedQuantity, row.record().quantity());
                item.refundedAmount = Math.addExact(item.refundedAmount, row.record().amountCent());
            }
        }
        Map<Long, OrderItemAfterSaleResponse> result = new HashMap<>();
        grouped.forEach((id, item) -> result.put(id, new OrderItemAfterSaleResponse(
                item.refundedQuantity, item.refundedAmount,
                item.paidBasis > 0 && item.refundedQuantity >= item.quantity && item.refundedAmount >= item.paidBasis,
                List.copyOf(item.records))));
        return result;
    }

    private record Row(long itemId, int quantity, long paidBasis, boolean refunded,
                       OrderItemAfterSaleResponse.Record record) { }

    private static class Accumulator {
        final int quantity;
        final long paidBasis;
        final List<OrderItemAfterSaleResponse.Record> records = new ArrayList<>();
        int refundedQuantity;
        long refundedAmount;

        Accumulator(int quantity, long paidBasis) {
            this.quantity = quantity;
            this.paidBasis = paidBasis;
        }
    }
}
