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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrderCloseService {

    private final JdbcClient jdbcClient;

    public OrderCloseService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void closeCreatedOrder(Long orderId, String closeReason, String operatorType, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
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
        if (!OrderStatus.CREATED.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        List<Long> orderItemIds = jdbcClient.sql("""
                        select id
                        from order_item
                        where order_id = :orderId
                        order by id asc
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();

        List<LockedStockRow> lockedStocks = jdbcClient.sql("""
                        select id as stock_lock_id,
                               order_item_id,
                               sku_id,
                               quantity
                        from stock_lock
                        where order_id = :orderId
                          and status = :status
                        order by id asc
                        for update
                        """)
                .param("orderId", orderId)
                .param("status", StockLockStatus.LOCKED.name())
                .query(this::mapLockedStockRow)
                .list();

        Set<Long> expectedOrderItemIds = new LinkedHashSet<>(orderItemIds);
        Set<Long> lockedOrderItemIds = new LinkedHashSet<>();
        for (LockedStockRow lockedStock : lockedStocks) {
            lockedOrderItemIds.add(lockedStock.orderItemId());
        }
        if (lockedStocks.size() != orderItemIds.size() || !expectedOrderItemIds.equals(lockedOrderItemIds)) {
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
                                released_at = :releasedAt,
                                updated_at = :updatedAt
                            where id = :userCouponId
                              and locked_order_id = :orderId
                              and status = :expectedStatus
                            """)
                    .param("status", UserCouponStatus.RELEASED.name())
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
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = :expectedStatus
                        """)
                .param("status", OrderStatus.CLOSED.name())
                .param("closeReason", closeReason)
                .param("closedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .param("expectedStatus", OrderStatus.CREATED.name())
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void insertStockLog(
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
                            sku_id, change_type, quantity_before, quantity_delta, quantity_after, reason, operator_type, operator_id
                        )
                        values (
                            :skuId, :changeType, :quantityBefore, :quantityDelta, :quantityAfter, :reason, :operatorType, :operatorId
                        )
                        """)
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

    private LockedStockRow mapLockedStockRow(ResultSet rs, int rowNum) throws SQLException {
        return new LockedStockRow(
                rs.getLong("stock_lock_id"),
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private record ClosableOrder(Long orderId, String status, Long userCouponId) {
    }

    private record LockedStockRow(Long stockLockId, Long orderItemId, Long skuId, Integer quantity) {
    }
}
