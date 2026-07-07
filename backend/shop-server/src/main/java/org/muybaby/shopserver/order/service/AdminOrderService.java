package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.order.dto.AdminOrderQueryRequest;
import org.muybaby.shopserver.order.dto.OrderDetailResponse;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminOrderService {

    private static final String OPERATOR_TYPE_ADMIN = "ADMIN";
    private static final String CLOSE_REASON_ADMIN = "ADMIN_CLOSE";

    private final JdbcClient jdbcClient;

    public AdminOrderService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<OrderSummaryResponse> page(AuthenticatedPrincipal principal, AdminOrderQueryRequest query) {
        requireAdminUser(principal);
        AdminOrderQueryRequest normalizedQuery = query == null
                ? new AdminOrderQueryRequest(null, null, null, null)
                : query;
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;
        String orderNoLike = StringUtils.hasText(normalizedQuery.orderNo()) ? "%" + normalizedQuery.orderNo().trim() + "%" : null;
        String status = StringUtils.hasText(normalizedQuery.status()) ? normalizedQuery.status().trim() : null;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where (:status is null or status = :status)
                          and (:orderNoLike is null or order_no like :orderNoLike)
                        """)
                .param("status", status)
                .param("orderNoLike", orderNoLike)
                .query(Long.class)
                .single();

        List<OrderSummaryResponse> records = jdbcClient.sql("""
                        select o.id as order_id,
                               o.order_no,
                               o.status,
                               o.product_amount_cent,
                               o.coupon_discount_cent,
                               o.freight_cent,
                               o.payable_amount_cent,
                               o.paid_amount_cent,
                               coalesce((
                                   select oi.product_title
                                   from order_item oi
                                   where oi.order_id = o.id
                                   order by oi.id asc
                                   limit 1
                               ), '') as product_title,
                               coalesce((
                                   select sum(oi.quantity)
                                   from order_item oi
                                   where oi.order_id = o.id
                               ), 0) as item_count,
                               o.created_at
                        from shop_order o
                        where (:status is null or o.status = :status)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                        order by o.created_at desc, o.id desc
                        limit :limit offset :offset
                        """)
                .param("status", status)
                .param("orderNoLike", orderNoLike)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapOrderSummary)
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public OrderDetailResponse detail(AuthenticatedPrincipal principal, Long orderId) {
        requireAdminUser(principal);
        OrderDetailHeader header = jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               status,
                               source,
                               product_original_amount_cent,
                               product_amount_cent,
                               user_coupon_id,
                               coupon_name,
                               coupon_discount_cent,
                               freight_cent,
                               payable_amount_cent,
                               paid_amount_cent,
                               receiver_name,
                               receiver_phone,
                               receiver_address,
                               payment_transaction_id,
                               merchant_trade_no,
                               close_reason,
                               closed_at,
                               created_at
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderDetailHeader)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        List<OrderItemResponse> items = jdbcClient.sql("""
                        select id as order_item_id,
                               sku_id,
                               spu_id,
                               product_title,
                               product_subtitle,
                               main_image,
                               sku_image,
                               display_image,
                               sku_code,
                               spec_text,
                               original_price_cent,
                               unit_price_cent,
                               quantity,
                               line_original_amount_cent,
                               line_amount_cent
                        from order_item
                        where order_id = :orderId
                        order by id asc
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderItem)
                .list();

        return new OrderDetailResponse(
                header.orderId(),
                header.orderNo(),
                header.status(),
                header.source(),
                header.productOriginalAmountCent(),
                header.productAmountCent(),
                header.userCouponId(),
                header.couponName(),
                header.couponDiscountCent(),
                header.freightCent(),
                header.payableAmountCent(),
                header.paidAmountCent(),
                header.receiverName(),
                header.receiverPhone(),
                header.receiverAddress(),
                header.paymentTransactionId(),
                header.merchantTradeNo(),
                header.closeReason(),
                header.closedAt(),
                header.createdAt(),
                items
        );
    }

    @Transactional
    public void closeCreatedOrder(AuthenticatedPrincipal principal, Long orderId) {
        Long adminUserId = requireAdminUser(principal);
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

        List<LockedStockRow> lockedStocks = jdbcClient.sql("""
                        select id as stock_lock_id,
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
            jdbcClient.sql("""
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
            insertStockLog(
                    lockedStock.skuId(),
                    StockChangeType.ORDER_RELEASE.name(),
                    quantityBefore,
                    lockedStock.quantity(),
                    quantityAfter,
                    "Admin close order " + orderId,
                    adminUserId
            );
        }

        if (order.userCouponId() != null) {
            jdbcClient.sql("""
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
                .param("closeReason", CLOSE_REASON_ADMIN)
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
                .param("operatorType", OPERATOR_TYPE_ADMIN)
                .param("operatorId", operatorId)
                .update();
    }

    private Long requireAdminUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private OrderSummaryResponse mapOrderSummary(ResultSet rs, int rowNum) throws SQLException {
        return new OrderSummaryResponse(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("product_amount_cent"),
                rs.getLong("coupon_discount_cent"),
                rs.getLong("freight_cent"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getString("product_title"),
                rs.getInt("item_count"),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private OrderDetailHeader mapOrderDetailHeader(ResultSet rs, int rowNum) throws SQLException {
        return new OrderDetailHeader(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getLong("product_original_amount_cent"),
                rs.getLong("product_amount_cent"),
                rs.getObject("user_coupon_id", Long.class),
                rs.getString("coupon_name"),
                rs.getLong("coupon_discount_cent"),
                rs.getLong("freight_cent"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getString("receiver_name"),
                rs.getString("receiver_phone"),
                rs.getString("receiver_address"),
                rs.getString("payment_transaction_id"),
                rs.getString("merchant_trade_no"),
                rs.getString("close_reason"),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private OrderItemResponse mapOrderItem(ResultSet rs, int rowNum) throws SQLException {
        return new OrderItemResponse(
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getLong("spu_id"),
                rs.getString("product_title"),
                rs.getString("product_subtitle"),
                rs.getString("main_image"),
                rs.getString("sku_image"),
                rs.getString("display_image"),
                rs.getString("sku_code"),
                rs.getString("spec_text"),
                rs.getLong("original_price_cent"),
                rs.getLong("unit_price_cent"),
                rs.getInt("quantity"),
                rs.getLong("line_original_amount_cent"),
                rs.getLong("line_amount_cent")
        );
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
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private record OrderDetailHeader(
            Long orderId,
            String orderNo,
            String status,
            String source,
            Long productOriginalAmountCent,
            Long productAmountCent,
            Long userCouponId,
            String couponName,
            Long couponDiscountCent,
            Long freightCent,
            Long payableAmountCent,
            Long paidAmountCent,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String paymentTransactionId,
            String merchantTradeNo,
            String closeReason,
            LocalDateTime closedAt,
            LocalDateTime createdAt
    ) {
    }

    private record ClosableOrder(Long orderId, String status, Long userCouponId) {
    }

    private record LockedStockRow(Long stockLockId, Long skuId, Integer quantity) {
    }
}
