package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadRecovery;
import org.muybaby.shopserver.order.dto.AdminOrderQueryRequest;
import org.muybaby.shopserver.order.dto.OrderDetailResponse;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminOrderService {

    private static final String CLOSE_REASON_ADMIN = "ADMIN_CLOSE";
    private static final String OPERATOR_TYPE_ADMIN = "ADMIN";

    private final JdbcClient jdbcClient;
    private final OrderCloseService orderCloseService;
    private final WechatShippingUploadRecovery shippingUploadRecovery;

    public AdminOrderService(
            JdbcClient jdbcClient,
            OrderCloseService orderCloseService,
            WechatShippingUploadRecovery shippingUploadRecovery
    ) {
        this.jdbcClient = jdbcClient;
        this.orderCloseService = orderCloseService;
        this.shippingUploadRecovery = shippingUploadRecovery;
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
        shippingUploadRecovery.reconcileOrder(orderId);
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
                               paid_at,
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
                               main_image_file_id,
                               sku_image,
                               sku_image_file_id,
                               display_image,
                               display_image_file_id,
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

        PaymentOrderSnapshot paymentOrder = findLatestPaymentOrder(orderId);
        String transactionId = nonBlank(
                paymentOrder == null ? null : paymentOrder.transactionId(),
                header.paymentTransactionId()
        );
        String outTradeNo = nonBlank(
                paymentOrder == null ? null : paymentOrder.outTradeNo(),
                header.merchantTradeNo()
        );
        LocalDateTime paidAt = paymentOrder == null || paymentOrder.paidAt() == null
                ? header.paidAt()
                : paymentOrder.paidAt();

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
                transactionId,
                header.merchantTradeNo(),
                paymentOrder == null ? null : paymentOrder.status(),
                outTradeNo,
                transactionId,
                paidAt,
                header.closeReason(),
                header.closedAt(),
                header.createdAt(),
                findShipment(orderId),
                items
        );
    }

    public void closeCreatedOrder(AuthenticatedPrincipal principal, Long orderId) {
        Long adminUserId = requireAdminUser(principal);
        orderCloseService.closeCreatedOrder(orderId, CLOSE_REASON_ADMIN, OPERATOR_TYPE_ADMIN, adminUserId);
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
                rs.getObject("paid_at", LocalDateTime.class),
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
                rs.getObject("main_image_file_id", Long.class),
                rs.getString("sku_image"),
                rs.getObject("sku_image_file_id", Long.class),
                rs.getString("display_image"),
                rs.getObject("display_image_file_id", Long.class),
                rs.getString("sku_code"),
                rs.getString("spec_text"),
                rs.getLong("original_price_cent"),
                rs.getLong("unit_price_cent"),
                rs.getInt("quantity"),
                rs.getLong("line_original_amount_cent"),
                rs.getLong("line_amount_cent")
        );
    }

    private PaymentOrderSnapshot findLatestPaymentOrder(Long orderId) {
        return jdbcClient.sql("""
                        select out_trade_no,
                               transaction_id,
                               status,
                               paid_at
                        from payment_order
                        where order_id = :orderId
                        order by updated_at desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentOrderSnapshot(
                        rs.getString("out_trade_no"),
                        rs.getString("transaction_id"),
                        rs.getString("status"),
                        rs.getObject("paid_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
    }

    private String nonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private OrderShipmentResponse findShipment(Long orderId) {
        return jdbcClient.sql("""
                        select id as shipment_id,
                               order_id,
                               logistics_type,
                               delivery_mode,
                               item_desc,
                               express_company_code,
                               express_company_name,
                               tracking_no,
                               shipment_note,
                               status as local_shipment_status,
                               wechat_provider_mode,
                               wechat_upload_status,
                               wechat_error_code,
                               wechat_error_message,
                               retry_count,
                               shipped_at,
                               upload_time,
                               wechat_uploaded_at,
                               last_attempt_at
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .optional()
                .orElse(null);
    }

    private OrderShipmentResponse mapShipment(ResultSet rs, int rowNum) throws SQLException {
        return new OrderShipmentResponse(
                rs.getLong("shipment_id"),
                rs.getLong("order_id"),
                LogisticsType.fromValue(rs.getInt("logistics_type")),
                DeliveryMode.fromValue(rs.getInt("delivery_mode")),
                rs.getString("item_desc"),
                rs.getString("express_company_code"),
                rs.getString("express_company_name"),
                rs.getString("tracking_no"),
                blankToNull(rs.getString("shipment_note")),
                rs.getString("local_shipment_status"),
                providerMode(rs.getString("wechat_provider_mode")),
                uploadStatus(rs.getString("wechat_upload_status")),
                blankToNull(rs.getString("wechat_error_code")),
                blankToNull(rs.getString("wechat_error_message")),
                rs.getInt("retry_count"),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getString("upload_time"),
                rs.getObject("wechat_uploaded_at", LocalDateTime.class),
                rs.getObject("last_attempt_at", LocalDateTime.class)
        );
    }

    private WechatProviderMode providerMode(String value) {
        try {
            return WechatProviderMode.valueOf(value);
        } catch (RuntimeException ex) {
            return WechatProviderMode.UNKNOWN;
        }
    }

    private WechatShippingUploadStatus uploadStatus(String value) {
        try {
            return WechatShippingUploadStatus.valueOf(value);
        } catch (RuntimeException ex) {
            return WechatShippingUploadStatus.UNKNOWN;
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
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
            LocalDateTime paidAt,
            String closeReason,
            LocalDateTime closedAt,
            LocalDateTime createdAt
    ) {
    }

    private record PaymentOrderSnapshot(
            String outTradeNo,
            String transactionId,
            String status,
            LocalDateTime paidAt
    ) {
    }
}
