package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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
        restockItems(refundOrderId, orderId, restockedAt);
    }

    private void restockItems(long refundOrderId, long orderId, LocalDateTime restockedAt) {
        List<RestockItem> items = jdbcClient.sql("""
                        select asi.id as after_sale_item_id,
                               asi.order_item_id,
                               asi.sku_id,
                               asi.restock_quantity
                        from refund_order ro
                        join after_sale_item asi on asi.after_sale_id = ro.after_sale_id
                        where ro.id = :refundOrderId
                          and ro.order_id = :orderId
                          and asi.restock_quantity > 0
                        order by asi.sku_id, asi.order_item_id
                        """)
                .param("refundOrderId", refundOrderId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new RestockItem(
                        rs.getLong("after_sale_item_id"),
                        rs.getLong("order_item_id"),
                        rs.getLong("sku_id"),
                        rs.getInt("restock_quantity")))
                .list();
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        Map<Long, Integer> expectedByOrderItem = new LinkedHashMap<>();
        for (RestockItem item : items) {
            if (item.quantity() <= 0
                    || expectedByOrderItem.putIfAbsent(item.orderItemId(), item.quantity()) != null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }
        Map<Long, Integer> existingByOrderItem = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select order_item_id, quantity
                        from refund_inventory_restock_item
                        where refund_order_id = :refundOrderId
                        order by order_item_id
                        for update
                        """)
                .param("refundOrderId", refundOrderId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("order_item_id"), rs.getInt("quantity")))
                .list()
                .forEach(entry -> existingByOrderItem.put(entry.getKey(), entry.getValue()));
        if (!existingByOrderItem.isEmpty()) {
            if (!existingByOrderItem.equals(expectedByOrderItem)) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            markRefundRestocked(refundOrderId, restockedAt);
            return;
        }

        List<Long> orderItemIds = items.stream().map(RestockItem::orderItemId).toList();
        List<RestockStockLock> locks = jdbcClient.sql("""
                        select id as stock_lock_id, order_item_id, sku_id, quantity,
                               status, restocked_quantity
                        from stock_lock
                        where order_id = :orderId and order_item_id in (:orderItemIds)
                        order by sku_id, id
                        for update
                        """)
                .param("orderId", orderId)
                .param("orderItemIds", orderItemIds)
                .query((rs, rowNum) -> new RestockStockLock(
                        rs.getLong("stock_lock_id"), rs.getLong("order_item_id"),
                        rs.getLong("sku_id"), rs.getInt("quantity"),
                        rs.getString("status"), rs.getInt("restocked_quantity")))
                .list();
        Map<Long, RestockStockLock> lockByOrderItem = new LinkedHashMap<>();
        for (RestockStockLock lock : locks) {
            if (lockByOrderItem.putIfAbsent(lock.orderItemId(), lock) != null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }
        if (lockByOrderItem.size() != items.size()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        Map<Long, Integer> quantityBySku = new TreeMap<>();
        for (RestockItem item : items) {
            RestockStockLock lock = lockByOrderItem.get(item.orderItemId());
            if (lock == null || lock.skuId() != item.skuId()
                    || lock.restockedQuantity() < 0
                    || lock.restockedQuantity() > lock.quantity()
                    || item.quantity() > lock.quantity() - lock.restockedQuantity()
                    || !(StockLockStatus.CONFIRMED.name().equals(lock.status())
                    || StockLockStatus.PARTIALLY_RESTOCKED.name().equals(lock.status()))) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            quantityBySku.merge(item.skuId(), item.quantity(), Math::addExact);
        }

        for (Map.Entry<Long, Integer> entry : quantityBySku.entrySet()) {
            long skuId = entry.getKey();
            int quantity = entry.getValue();
            int quantityBefore = jdbcClient.sql("""
                            select stock_available from product_sku
                            where id = :skuId for update
                            """)
                    .param("skuId", skuId)
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
            int quantityAfter;
            try {
                quantityAfter = Math.addExact(quantityBefore, quantity);
            } catch (ArithmeticException exception) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            requireOne(jdbcClient.sql("""
                            update product_sku
                            set stock_available = :quantityAfter, updated_at = :updatedAt
                            where id = :skuId and stock_available = :quantityBefore
                            """)
                    .param("quantityAfter", quantityAfter)
                    .param("updatedAt", restockedAt)
                    .param("skuId", skuId)
                    .param("quantityBefore", quantityBefore)
                    .update());
            insertStockLog(
                    refundOrderId, orderId, skuId,
                    quantityBefore, quantity, quantityAfter, restockedAt);
        }

        for (RestockItem item : items) {
            RestockStockLock lock = lockByOrderItem.get(item.orderItemId());
            int restockedQuantity = Math.addExact(lock.restockedQuantity(), item.quantity());
            String status = restockedQuantity == lock.quantity()
                    ? StockLockStatus.RESTOCKED.name()
                    : StockLockStatus.PARTIALLY_RESTOCKED.name();
            requireOne(jdbcClient.sql("""
                            update stock_lock
                            set status = :status,
                                restocked_quantity = :restockedQuantity,
                                restock_refund_order_id = case
                                    when :status = :fullyRestocked then :refundOrderId
                                    else restock_refund_order_id end,
                                restocked_at = case
                                    when :status = :fullyRestocked then :restockedAt
                                    else restocked_at end,
                                updated_at = :restockedAt
                            where id = :stockLockId
                              and restocked_quantity = :previousRestockedQuantity
                              and status in (:eligibleStatuses)
                            """)
                    .param("status", status)
                    .param("restockedQuantity", restockedQuantity)
                    .param("fullyRestocked", StockLockStatus.RESTOCKED.name())
                    .param("refundOrderId", refundOrderId)
                    .param("restockedAt", restockedAt)
                    .param("stockLockId", lock.stockLockId())
                    .param("previousRestockedQuantity", lock.restockedQuantity())
                    .param("eligibleStatuses", List.of(
                            StockLockStatus.CONFIRMED.name(),
                            StockLockStatus.PARTIALLY_RESTOCKED.name()))
                    .update());
            requireOne(jdbcClient.sql("""
                            insert into refund_inventory_restock_item (
                                refund_order_id, after_sale_item_id, order_item_id,
                                stock_lock_id, sku_id, quantity, restocked_at, created_at
                            ) values (
                                :refundOrderId, :afterSaleItemId, :orderItemId,
                                :stockLockId, :skuId, :quantity, :restockedAt, :restockedAt
                            )
                            """)
                    .param("refundOrderId", refundOrderId)
                    .param("afterSaleItemId", item.afterSaleItemId())
                    .param("orderItemId", item.orderItemId())
                    .param("stockLockId", lock.stockLockId())
                    .param("skuId", item.skuId())
                    .param("quantity", item.quantity())
                    .param("restockedAt", restockedAt)
                    .update());
        }
        markRefundRestocked(refundOrderId, restockedAt);
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

    private record RestockItem(
            long afterSaleItemId,
            long orderItemId,
            long skuId,
            int quantity
    ) {
    }

    private record RestockStockLock(
            long stockLockId,
            long orderItemId,
            long skuId,
            int quantity,
            String status,
            int restockedQuantity
    ) {
    }
}
