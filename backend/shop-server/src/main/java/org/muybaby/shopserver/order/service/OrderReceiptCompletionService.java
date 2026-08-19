package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.aftersale.service.AfterSaleFulfillmentPolicy;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class OrderReceiptCompletionService {

    private static final String OPERATOR_TYPE_APP = "APP";
    private static final String OPERATOR_TYPE_SYSTEM = "SYSTEM";

    private final JdbcClient jdbcClient;
    private final AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy;
    private final OrderStatusLogService orderStatusLogService;
    private final Clock clock;

    public OrderReceiptCompletionService(
            JdbcClient jdbcClient,
            AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy,
            OrderStatusLogService orderStatusLogService,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.afterSaleFulfillmentPolicy = afterSaleFulfillmentPolicy;
        this.orderStatusLogService = orderStatusLogService;
        this.clock = clock;
    }

    @Transactional
    public OrderReceiptResponse completeForUser(Long userId, Long orderId) {
        ReceiptOrder order = lockOwnedVisibleOrder(userId, orderId);
        if (OrderStatus.COMPLETED.name().equals(order.status())) {
            return response(order);
        }
        if (!OrderStatus.SHIPPED.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        afterSaleFulfillmentPolicy.rejectIfBlocked(order.orderId());
        LocalDateTime completedAt = complete(
                order.orderId(), OPERATOR_TYPE_APP, userId,
                "ORDER_COMPLETED", "用户确认收货"
        );
        return new OrderReceiptResponse(
                order.orderId(), OrderStatus.COMPLETED.name(), completedAt
        );
    }

    @Transactional
    public OrderReceiptResponse completeForUserWithLocalFallback(Long userId, Long orderId) {
        ReceiptOrder order = lockOwnedVisibleOrder(userId, orderId);
        if (OrderStatus.COMPLETED.name().equals(order.status())) {
            return response(order);
        }
        if (!OrderStatus.SHIPPED.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        afterSaleFulfillmentPolicy.rejectIfBlocked(order.orderId());
        java.util.List<ReceiptShipment> shipments = lockShipments(order.orderId());
        if (shipments.isEmpty() || shipments.stream().anyMatch(shipment -> !shipment.permitsLocalFallback())) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        }
        LocalDateTime completedAt = complete(
                order.orderId(), OPERATOR_TYPE_APP, userId,
                "ORDER_COMPLETED_LOCAL_FALLBACK",
                "用户确认收货（微信发货未上传，本地兜底）"
        );
        return new OrderReceiptResponse(
                order.orderId(), OrderStatus.COMPLETED.name(), completedAt
        );
    }

    @Transactional
    public boolean completeAutomatically(Long orderId) {
        ReceiptOrder order = lockOrder(orderId);
        if (order == null || !OrderStatus.SHIPPED.name().equals(order.status())) {
            return false;
        }
        if (afterSaleFulfillmentPolicy.findBlocking(order.orderId()).isPresent()) {
            return false;
        }
        complete(
                order.orderId(), OPERATOR_TYPE_SYSTEM, 0L,
                "ORDER_AUTO_COMPLETED", "微信自动确认收货同步"
        );
        return true;
    }

    private ReceiptOrder lockOwnedVisibleOrder(Long userId, Long orderId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               status,
                               completed_at
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                          and app_deleted_at is null
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query((rs, rowNum) -> new ReceiptOrder(
                        rs.getLong("order_id"),
                        rs.getString("status"),
                        rs.getObject("completed_at", LocalDateTime.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private ReceiptOrder lockOrder(Long orderId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               status,
                               completed_at
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ReceiptOrder(
                        rs.getLong("order_id"),
                        rs.getString("status"),
                        rs.getObject("completed_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
    }

    private java.util.List<ReceiptShipment> lockShipments(Long orderId) {
        return jdbcClient.sql("""
                        select wechat_provider_mode, wechat_upload_status
                        from order_shipment
                        where order_id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ReceiptShipment(
                        providerMode(rs.getString("wechat_provider_mode")),
                        uploadStatus(rs.getString("wechat_upload_status"))
                ))
                .list();
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

    private LocalDateTime complete(
            Long orderId,
            String operatorType,
            Long operatorId,
            String eventType,
            String description
    ) {
        LocalDateTime completedAt = LocalDateTime.now(clock).withNano(0);
        int updated = jdbcClient.sql("""
                        update shop_order
                        set status = :completedStatus,
                            completed_at = :completedAt,
                            updated_at = :completedAt
                        where id = :orderId
                          and status = :shippedStatus
                        """)
                .param("completedStatus", OrderStatus.COMPLETED.name())
                .param("completedAt", completedAt)
                .param("orderId", orderId)
                .param("shippedStatus", OrderStatus.SHIPPED.name())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        orderStatusLogService.record(
                orderId, OrderStatus.SHIPPED.name(), OrderStatus.COMPLETED.name(),
                eventType, operatorType, operatorId, description, completedAt
        );
        return completedAt;
    }

    private OrderReceiptResponse response(ReceiptOrder order) {
        return new OrderReceiptResponse(
                order.orderId(), order.status(), order.completedAt()
        );
    }

    private record ReceiptOrder(
            Long orderId,
            String status,
            LocalDateTime completedAt
    ) {
    }

    private record ReceiptShipment(
            WechatProviderMode providerMode,
            WechatShippingUploadStatus uploadStatus
    ) {
        boolean permitsLocalFallback() {
            if (providerMode != WechatProviderMode.REAL) {
                return true;
            }
            return uploadStatus == WechatShippingUploadStatus.PENDING
                    || uploadStatus == WechatShippingUploadStatus.SKIPPED
                    || uploadStatus == WechatShippingUploadStatus.FAILED
                    || uploadStatus == WechatShippingUploadStatus.UNAVAILABLE;
        }
    }
}
