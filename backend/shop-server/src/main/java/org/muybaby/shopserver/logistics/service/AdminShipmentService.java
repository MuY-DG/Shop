package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.ShipmentStatus;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.provider.RealWechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

@Service
public class AdminShipmentService {

    private static final Logger log = LoggerFactory.getLogger(AdminShipmentService.class);

    private final JdbcClient jdbcClient;
    private final ShippingProperties shippingProperties;
    private final WechatShippingProvider wechatShippingProvider;

    public AdminShipmentService(
            JdbcClient jdbcClient,
            ShippingProperties shippingProperties,
            WechatShippingProvider wechatShippingProvider
    ) {
        this.jdbcClient = jdbcClient;
        this.shippingProperties = shippingProperties;
        this.wechatShippingProvider = wechatShippingProvider;
    }

    @Transactional
    public OrderShipmentResponse ship(AuthenticatedPrincipal principal, Long orderId, AdminShipOrderRequest request) {
        requireAdminUser(principal);
        OrderShipmentContext context = requirePaidOrder(orderId);
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcClient.sql("""
                            insert into order_shipment
                                (order_id, express_company, tracking_no, shipment_note, status,
                                 wechat_upload_status, wechat_error_code, wechat_error_message,
                                 retry_count, shipped_at, created_at, updated_at)
                            values
                                (:orderId, :expressCompany, :trackingNo, :shipmentNote, :status,
                                 :wechatUploadStatus, '', '', 0, :shippedAt, :createdAt, :updatedAt)
                            """)
                    .param("orderId", orderId)
                    .param("expressCompany", request.expressCompany().trim())
                    .param("trackingNo", request.trackingNo().trim())
                    .param("shipmentNote", defaultString(request.shipmentNote()).trim())
                    .param("status", ShipmentStatus.SHIPPED.name())
                    .param("wechatUploadStatus", WechatShippingUploadStatus.SKIPPED.name())
                    .param("shippedAt", now)
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        int updatedRows = jdbcClient.sql("""
                        update shop_order
                        set status = :status,
                            shipped_at = :shippedAt,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = :expectedStatus
                        """)
                .param("status", OrderStatus.SHIPPED.name())
                .param("shippedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .param("expectedStatus", OrderStatus.PAID.name())
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        return refreshWechatUpload(orderId, context, false);
    }

    @Transactional
    public OrderShipmentResponse retryWechatUpload(AuthenticatedPrincipal principal, Long orderId) {
        requireAdminUser(principal);
        OrderShipmentContext context = requireShippedOrder(orderId);
        return refreshWechatUpload(orderId, context, true);
    }

    private OrderShipmentResponse refreshWechatUpload(Long orderId, OrderShipmentContext context, boolean retry) {
        if (!shippingProperties.isUploadEnabled()) {
            jdbcClient.sql("""
                            update order_shipment
                            set wechat_upload_status = :status,
                                wechat_error_code = '',
                                wechat_error_message = '',
                                updated_at = :updatedAt
                            where order_id = :orderId
                            """)
                    .param("status", WechatShippingUploadStatus.SKIPPED.name())
                    .param("updatedAt", LocalDateTime.now())
                    .param("orderId", orderId)
                    .update();
            return findShipment(orderId);
        }

        OrderShipmentResponse shipment = findShipment(orderId);
        WechatShippingUploadResult uploadResult;
        if (!StringUtils.hasText(context.transactionId())) {
            uploadResult = WechatShippingUploadResult.failed(
                    RealWechatShippingProvider.MISSING_TRANSACTION_ID,
                    RealWechatShippingProvider.MISSING_TRANSACTION_ID_MESSAGE
            );
        } else {
            uploadResult = uploadSafely(new WechatShippingUploadRequest(
                    orderId,
                    context.transactionId(),
                    context.outTradeNo(),
                    context.openid(),
                    shipment.expressCompany(),
                    shipment.trackingNo(),
                    shipment.shipmentNote()
            ));
        }

        LocalDateTime now = LocalDateTime.now();
        int nextRetryCount = retry || !uploadResult.success() ? shipment.retryCount() + 1 : shipment.retryCount();
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :status,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            retry_count = :retryCount,
                            wechat_uploaded_at = :wechatUploadedAt,
                            updated_at = :updatedAt
                        where order_id = :orderId
                        """)
                .param("status", uploadResult.success()
                        ? WechatShippingUploadStatus.UPLOADED.name()
                        : WechatShippingUploadStatus.FAILED.name())
                .param("errorCode", uploadResult.success() ? "" : uploadResult.errorCode())
                .param("errorMessage", uploadResult.success() ? "" : truncate(uploadResult.errorMessage(), 255))
                .param("retryCount", nextRetryCount)
                .param("wechatUploadedAt", uploadResult.success() ? now : null)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .update();
        return findShipment(orderId);
    }

    private WechatShippingUploadResult uploadSafely(WechatShippingUploadRequest request) {
        try {
            return wechatShippingProvider.upload(request);
        } catch (BusinessException ex) {
            return uploadExceptionResult(ex);
        } catch (RuntimeException ex) {
            return uploadExceptionResult(ex);
        }
    }

    private WechatShippingUploadResult uploadExceptionResult(RuntimeException ex) {
        log.warn("WeChat shipping upload failed after local shipment: exception={}", ex.getClass().getSimpleName());
        return WechatShippingUploadResult.failed(
                ErrorCode.WECHAT_SHIPPING_UPLOAD_FAILED.name(),
                ErrorCode.WECHAT_SHIPPING_UPLOAD_FAILED.message()
        );
    }

    private OrderShipmentContext requirePaidOrder(Long orderId) {
        OrderShipmentContext context = findOrderContext(orderId);
        if (!OrderStatus.PAID.name().equals(context.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return context;
    }

    private OrderShipmentContext requireShippedOrder(Long orderId) {
        OrderShipmentContext context = findOrderContext(orderId);
        if (!OrderStatus.SHIPPED.name().equals(context.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        findShipment(orderId);
        return context;
    }

    private OrderShipmentContext findOrderContext(Long orderId) {
        return jdbcClient.sql("""
                        select o.id as order_id,
                               o.status,
                               coalesce(nullif(po.transaction_id, ''), o.payment_transaction_id, '') as transaction_id,
                               coalesce(nullif(po.out_trade_no, ''), o.merchant_trade_no, '') as out_trade_no,
                               coalesce(nullif(po.payer_openid, ''), u.openid, '') as openid
                        from shop_order o
                        join app_user u on u.id = o.user_id
                        left join payment_order po on po.order_id = o.id and po.status = 'PAID'
                        where o.id = :orderId
                        order by po.id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderShipmentContext)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private OrderShipmentResponse findShipment(Long orderId) {
        return jdbcClient.sql("""
                        select id as shipment_id,
                               order_id,
                               express_company,
                               tracking_no,
                               shipment_note,
                               status,
                               wechat_upload_status,
                               wechat_error_code,
                               wechat_error_message,
                               retry_count,
                               shipped_at,
                               wechat_uploaded_at
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private Long requireAdminUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private OrderShipmentResponse mapShipment(ResultSet rs, int rowNum) throws SQLException {
        return new OrderShipmentResponse(
                rs.getLong("shipment_id"),
                rs.getLong("order_id"),
                rs.getString("express_company"),
                rs.getString("tracking_no"),
                rs.getString("shipment_note"),
                rs.getString("status"),
                rs.getString("wechat_upload_status"),
                rs.getString("wechat_error_code"),
                rs.getString("wechat_error_message"),
                rs.getInt("retry_count"),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getObject("wechat_uploaded_at", LocalDateTime.class)
        );
    }

    private OrderShipmentContext mapOrderShipmentContext(ResultSet rs, int rowNum) throws SQLException {
        return new OrderShipmentContext(
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getString("transaction_id"),
                rs.getString("out_trade_no"),
                rs.getString("openid")
        );
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        String safeValue = defaultString(value);
        return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, maxLength);
    }

    private record OrderShipmentContext(
            Long orderId,
            String status,
            String transactionId,
            String outTradeNo,
            String openid
    ) {
    }
}
