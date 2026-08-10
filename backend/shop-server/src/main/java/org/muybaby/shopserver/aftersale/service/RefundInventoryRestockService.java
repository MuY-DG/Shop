package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class RefundInventoryRestockService {

    private final JdbcClient jdbcClient;

    public RefundInventoryRestockService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void restockIfRequired(
            long refundOrderId,
            long orderId,
            boolean restockRequired,
            LocalDateTime restockedAt
    ) {
        if (!restockRequired) {
            return;
        }
        requireUnshippedOrder(orderId);

        List<OrderItemSnapshot> orderItems = jdbcClient.sql("""
                        select id as order_item_id, sku_id, quantity
                        from order_item
                        where order_id = :orderId
                        order by id
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderItem)
                .list();
        List<StockLockSnapshot> stockLocks = jdbcClient.sql("""
                        select id as stock_lock_id,
                               order_item_id,
                               sku_id,
                               quantity,
                               status,
                               restock_refund_order_id
                        from stock_lock
                        where order_id = :orderId
                        order by sku_id, id
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapStockLock)
                .list();
        validateCompleteMapping(orderItems, stockLocks);

        boolean alreadyRestocked = stockLocks.stream().allMatch(lock ->
                StockLockStatus.RESTOCKED.name().equals(lock.status())
                        && Long.valueOf(refundOrderId).equals(lock.restockRefundOrderId()));
        if (alreadyRestocked) {
            markRefundRestocked(refundOrderId, restockedAt);
            return;
        }
        boolean allConfirmed = stockLocks.stream().allMatch(lock ->
                StockLockStatus.CONFIRMED.name().equals(lock.status())
                        && lock.restockRefundOrderId() == null);
        if (!allConfirmed) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        Map<Long, Integer> quantityBySku = new TreeMap<>();
        for (StockLockSnapshot lock : stockLocks) {
            quantityBySku.merge(lock.skuId(), lock.quantity(), Math::addExact);
        }

        for (Map.Entry<Long, Integer> entry : quantityBySku.entrySet()) {
            long skuId = entry.getKey();
            int quantity = entry.getValue();
            int quantityBefore = jdbcClient.sql("""
                            select stock_available
                            from product_sku
                            where id = :skuId
                            for update
                            """)
                    .param("skuId", skuId)
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
            int quantityAfter;
            try {
                quantityAfter = Math.addExact(quantityBefore, quantity);
            } catch (ArithmeticException ex) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            int updated = jdbcClient.sql("""
                            update product_sku
                            set stock_available = :quantityAfter,
                                updated_at = :updatedAt
                            where id = :skuId
                              and stock_available = :quantityBefore
                            """)
                    .param("quantityAfter", quantityAfter)
                    .param("updatedAt", restockedAt)
                    .param("skuId", skuId)
                    .param("quantityBefore", quantityBefore)
                    .update();
            requireOne(updated);
            insertStockLog(
                    refundOrderId, orderId, skuId,
                    quantityBefore, quantity, quantityAfter, restockedAt);
        }

        for (StockLockSnapshot lock : stockLocks) {
            int updated = jdbcClient.sql("""
                            update stock_lock
                            set status = :restocked,
                                restock_refund_order_id = :refundOrderId,
                                restocked_at = :restockedAt,
                                updated_at = :restockedAt
                            where id = :stockLockId
                              and status = :confirmed
                              and restock_refund_order_id is null
                            """)
                    .param("restocked", StockLockStatus.RESTOCKED.name())
                    .param("refundOrderId", refundOrderId)
                    .param("restockedAt", restockedAt)
                    .param("stockLockId", lock.stockLockId())
                    .param("confirmed", StockLockStatus.CONFIRMED.name())
                    .update();
            requireOne(updated);
        }
        markRefundRestocked(refundOrderId, restockedAt);
    }

    private void requireUnshippedOrder(long orderId) {
        Long eligible = jdbcClient.sql("""
                        select count(*)
                        from shop_order order_entry
                        where order_entry.id = :orderId
                          and order_entry.shipped_at is null
                          and not exists (
                            select 1 from order_shipment shipment
                            where shipment.order_id = order_entry.id
                          )
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (eligible != 1L) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void validateCompleteMapping(
            List<OrderItemSnapshot> orderItems,
            List<StockLockSnapshot> stockLocks
    ) {
        if (orderItems.isEmpty() || orderItems.size() != stockLocks.size()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        Map<Long, OrderItemSnapshot> itemsById = new LinkedHashMap<>();
        for (OrderItemSnapshot item : orderItems) {
            if (itemsById.putIfAbsent(item.orderItemId(), item) != null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }
        Map<Long, StockLockSnapshot> locksByItemId = new LinkedHashMap<>();
        for (StockLockSnapshot lock : stockLocks) {
            if (locksByItemId.putIfAbsent(lock.orderItemId(), lock) != null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }
        for (OrderItemSnapshot item : orderItems) {
            StockLockSnapshot lock = locksByItemId.get(item.orderItemId());
            if (lock == null
                    || !item.skuId().equals(lock.skuId())
                    || item.quantity() != lock.quantity()) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }
    }

    private void insertStockLog(
            long refundOrderId,
            long orderId,
            long skuId,
            int quantityBefore,
            int quantity,
            int quantityAfter,
            LocalDateTime createdAt
    ) {
        jdbcClient.sql("""
                        insert into stock_log (
                            refund_order_id, order_id, sku_id, change_type,
                            quantity_before, quantity_delta, quantity_after,
                            reason, operator_type, operator_id, created_at
                        ) values (
                            :refundOrderId, :orderId, :skuId, :changeType,
                            :quantityBefore, :quantity, :quantityAfter,
                            :reason, 'WECHAT', 0, :createdAt
                        )
                        """)
                .param("refundOrderId", refundOrderId)
                .param("orderId", orderId)
                .param("skuId", skuId)
                .param("changeType", StockChangeType.REFUND_RESTOCK.name())
                .param("quantityBefore", quantityBefore)
                .param("quantity", quantity)
                .param("quantityAfter", quantityAfter)
                .param("reason", "Refund restock order " + orderId + " refund " + refundOrderId)
                .param("createdAt", createdAt)
                .update();
    }

    private void markRefundRestocked(long refundOrderId, LocalDateTime restockedAt) {
        int updated = jdbcClient.sql("""
                        update refund_order
                        set restocked_at = coalesce(restocked_at, :restockedAt),
                            updated_at = :restockedAt
                        where id = :refundOrderId
                          and restock_required = true
                        """)
                .param("restockedAt", restockedAt)
                .param("refundOrderId", refundOrderId)
                .update();
        requireOne(updated);
    }

    private void requireOne(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private OrderItemSnapshot mapOrderItem(ResultSet rs, int rowNum) throws SQLException {
        return new OrderItemSnapshot(
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private StockLockSnapshot mapStockLock(ResultSet rs, int rowNum) throws SQLException {
        return new StockLockSnapshot(
                rs.getLong("stock_lock_id"),
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity"),
                rs.getString("status"),
                rs.getObject("restock_refund_order_id", Long.class)
        );
    }

    private record OrderItemSnapshot(Long orderItemId, Long skuId, int quantity) {
    }

    private record StockLockSnapshot(
            Long stockLockId,
            Long orderItemId,
            Long skuId,
            int quantity,
            String status,
            Long restockRefundOrderId
    ) {
    }
}
