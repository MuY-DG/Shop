package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderCloseService {

    private final JdbcClient jdbcClient;
    private final OrderStatusLogService orderStatusLogService;

    public OrderCloseService(JdbcClient jdbcClient, OrderStatusLogService orderStatusLogService) {
        this.jdbcClient = jdbcClient;
        this.orderStatusLogService = orderStatusLogService;
    }

    @Transactional
    public void closeCreatedOrder(Long orderId, String closeReason, String operatorType, Long operatorId) {
        closeOrder(orderId, OrderStatus.CREATED, closeReason, operatorType, operatorId);
    }

    @Transactional
    public void closePayingOrder(Long orderId, String closeReason, String operatorType, Long operatorId) {
        closeOrder(orderId, OrderStatus.PAYING, closeReason, operatorType, operatorId);
    }

    private void closeOrder(Long orderId, OrderStatus expectedOrderStatus, String closeReason, String operatorType, Long operatorId) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        ClosableOrder order = jdbcClient.sql("""
                        select id as order_id,
                               status,
                               user_coupon_id
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapClosableOrder)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!expectedOrderStatus.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        List<OrderItemSnapshot> orderItems = jdbcClient.sql("""
                        select id as order_item_id,
                               sku_id,
                               quantity
                        from order_item
                        where order_id = :orderId
                        order by id asc
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderItemSnapshot)
                .list();

        List<StockLockSnapshot> stockLocks = jdbcClient.sql("""
                        select id as stock_lock_id,
                               order_item_id,
                               sku_id,
                               quantity,
                               status
                        from stock_lock
                        where order_id = :orderId
                        order by sku_id asc, id asc
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapStockLockSnapshot)
                .list();

        if (orderItems.size() != stockLocks.size()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        Map<Long, OrderItemSnapshot> orderItemById = new LinkedHashMap<>();
        for (OrderItemSnapshot orderItem : orderItems) {
            if (orderItemById.putIfAbsent(orderItem.orderItemId(), orderItem) != null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }

        Map<Long, LockedStockRow> lockedStockByOrderItemId = new LinkedHashMap<>();
        for (StockLockSnapshot stockLock : stockLocks) {
            if (!StockLockStatus.LOCKED.name().equals(stockLock.status())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            LockedStockRow prior = lockedStockByOrderItemId.putIfAbsent(
                    stockLock.orderItemId(),
                    new LockedStockRow(
                            stockLock.stockLockId(),
                            stockLock.orderItemId(),
                            stockLock.skuId(),
                            stockLock.quantity()
                    )
            );
            if (prior != null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }

        if (orderItemById.size() != lockedStockByOrderItemId.size()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        for (OrderItemSnapshot orderItem : orderItems) {
            LockedStockRow lockedStock = lockedStockByOrderItemId.get(orderItem.orderItemId());
            if (lockedStock == null
                    || !orderItem.skuId().equals(lockedStock.skuId())
                    || !orderItem.quantity().equals(lockedStock.quantity())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }

        List<LockedStockRow> lockedStocks = orderItems.stream()
                .map(orderItem -> lockedStockByOrderItemId.get(orderItem.orderItemId()))
                .sorted(Comparator.comparing(LockedStockRow::skuId)
                        .thenComparing(LockedStockRow::stockLockId))
                .toList();

        if (lockedStocks.size() != orderItems.size()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        for (LockedStockRow lockedStock : lockedStocks) {
            Integer quantityBefore = jdbcClient.sql("""
                            select stock_available
                            from product_sku
                            where id = :skuId
                            for update
                            """)
                    .param("skuId", lockedStock.skuId())
                    .query(Integer.class)
                    .single();
            int quantityAfter = quantityBefore + lockedStock.quantity();

            jdbcClient.sql("""
                            update product_sku
                            set stock_available = stock_available + :quantity,
                                updated_at = :updatedAt
                            where id = :skuId
                            """)
                    .param("quantity", lockedStock.quantity())
                    .param("updatedAt", now)
                    .param("skuId", lockedStock.skuId())
                    .update();
            int releasedStockLockRows = jdbcClient.sql("""
                            update stock_lock
                            set status = :status,
                                released_at = :releasedAt,
                                updated_at = :updatedAt
                            where id = :stockLockId
                              and status = :expectedStatus
                            """)
                    .param("status", StockLockStatus.RELEASED.name())
                    .param("releasedAt", now)
                    .param("updatedAt", now)
                    .param("stockLockId", lockedStock.stockLockId())
                    .param("expectedStatus", StockLockStatus.LOCKED.name())
                    .update();
            if (releasedStockLockRows != 1) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            insertStockLog(
                    orderId,
                    lockedStock.skuId(),
                    StockChangeType.ORDER_RELEASE.name(),
                    quantityBefore,
                    lockedStock.quantity(),
                    quantityAfter,
                    closeReason + " order " + orderId,
                    operatorType,
                    operatorId
            );
        }

        if (order.userCouponId() != null) {
            int releasedCouponRows = jdbcClient.sql("""
                            update user_coupon
                            set status = :status,
                                locked_order_id = null,
                                locked_at = null,
                                released_at = :releasedAt,
                                updated_at = :updatedAt
                            where id = :userCouponId
                              and locked_order_id = :orderId
                              and status = :expectedStatus
                            """)
                    .param("status", UserCouponStatus.CLAIMED.name())
                    .param("releasedAt", now)
                    .param("updatedAt", now)
                    .param("userCouponId", order.userCouponId())
                    .param("orderId", orderId)
                    .param("expectedStatus", UserCouponStatus.LOCKED.name())
                    .update();
            if (releasedCouponRows != 1) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        }

        int updatedRows = jdbcClient.sql("""
                        update shop_order
                        set status = :status,
                            close_reason = :closeReason,
                            closed_at = :closedAt,
                            created_timeout_claim_token = null,
                            created_timeout_claimed_at = null,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = :expectedStatus
                        """)
                .param("status", OrderStatus.CLOSED.name())
                .param("closeReason", closeReason)
                .param("closedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .param("expectedStatus", expectedOrderStatus.name())
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        orderStatusLogService.record(
                orderId, expectedOrderStatus.name(), OrderStatus.CLOSED.name(),
                "ORDER_CLOSED", operatorType, operatorId, closeReason, now
        );
    }

    private void insertStockLog(
            Long orderId,
            Long skuId,
            String changeType,
            Integer quantityBefore,
            Integer quantityDelta,
            Integer quantityAfter,
            String reason,
            String operatorType,
            Long operatorId
    ) {
        jdbcClient.sql("""
                        insert into stock_log (
                            order_id, sku_id, change_type, quantity_before, quantity_delta,
                            quantity_after, reason, operator_type, operator_id
                        )
                        values (
                            :orderId, :skuId, :changeType, :quantityBefore, :quantityDelta,
                            :quantityAfter, :reason, :operatorType, :operatorId
                        )
                        """)
                .param("orderId", orderId)
                .param("skuId", skuId)
                .param("changeType", changeType)
                .param("quantityBefore", quantityBefore)
                .param("quantityDelta", quantityDelta)
                .param("quantityAfter", quantityAfter)
                .param("reason", reason)
                .param("operatorType", operatorType)
                .param("operatorId", operatorId)
                .update();
    }

    private ClosableOrder mapClosableOrder(ResultSet rs, int rowNum) throws SQLException {
        return new ClosableOrder(
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getObject("user_coupon_id", Long.class)
        );
    }

    private OrderItemSnapshot mapOrderItemSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new OrderItemSnapshot(
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private StockLockSnapshot mapStockLockSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new StockLockSnapshot(
                rs.getLong("stock_lock_id"),
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity"),
                rs.getString("status")
        );
    }

    private record ClosableOrder(Long orderId, String status, Long userCouponId) {
    }

    private record OrderItemSnapshot(Long orderItemId, Long skuId, Integer quantity) {
    }

    private record StockLockSnapshot(Long stockLockId, Long orderItemId, Long skuId, Integer quantity, String status) {
    }

    private record LockedStockRow(Long stockLockId, Long orderItemId, Long skuId, Integer quantity) {
    }
}
