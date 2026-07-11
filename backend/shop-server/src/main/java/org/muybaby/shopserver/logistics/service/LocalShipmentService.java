package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentStatus;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

@Service
public class LocalShipmentService {

    private static final int MAX_ITEM_DESC_CODE_POINTS = 120;
    private static final String SF_DELIVERY_ID = "SF";

    private final JdbcClient jdbcClient;
    private final ShippingProperties shippingProperties;
    private final WechatShippingProvider shippingProvider;
    private final ShipmentContactMasker contactMasker;
    private final TransactionTemplate transactionTemplate;

    public LocalShipmentService(
            JdbcClient jdbcClient,
            ShippingProperties shippingProperties,
            WechatShippingProvider shippingProvider,
            ShipmentContactMasker contactMasker,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.shippingProperties = shippingProperties;
        this.shippingProvider = shippingProvider;
        this.contactMasker = contactMasker;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OrderShipmentResponse create(
            AuthenticatedPrincipal principal,
            long orderId,
            AdminShipOrderRequest request
    ) {
        requireAdmin(principal);
        WechatProviderMode initialProviderMode = initialProviderMode();
        return transactionTemplate.execute(status -> createInTransaction(
                orderId, request, initialProviderMode
        ));
    }

    private OrderShipmentResponse createInTransaction(
            long orderId,
            AdminShipOrderRequest request,
            WechatProviderMode initialProviderMode
    ) {
        OrderForShipment order = lockPaidOrder(orderId);
        NormalizedShipment shipment = normalize(request, order.receiverPhone());
        LocalDateTime now = LocalDateTime.now();

        try {
            jdbcClient.sql("""
                            insert into order_shipment(
                                order_id, logistics_type, delivery_mode, item_desc,
                                express_company_code, express_company_name, tracking_no,
                                consignor_contact, receiver_contact, shipment_note,
                                status, wechat_provider_mode, wechat_upload_status,
                                wechat_error_code, wechat_error_message, retry_count,
                                shipped_at, created_at, updated_at)
                            values (
                                :orderId, :logisticsType, :deliveryMode, :itemDesc,
                                :expressCompanyCode, :expressCompanyName, :trackingNo,
                                :consignorContact, :receiverContact, :shipmentNote,
                                :status, :providerMode, :uploadStatus,
                                '', '', 0, :shippedAt, :createdAt, :updatedAt)
                            """)
                    .param("orderId", orderId)
                    .param("logisticsType", shipment.logisticsType().value())
                    .param("deliveryMode", DeliveryMode.UNIFIED.value())
                    .param("itemDesc", shipment.itemDesc())
                    .param("expressCompanyCode", shipment.expressCompanyCode())
                    .param("expressCompanyName", shipment.expressCompanyName())
                    .param("trackingNo", shipment.trackingNo())
                    .param("consignorContact", shipment.consignorContact())
                    .param("receiverContact", shipment.receiverContact())
                    .param("shipmentNote", shipment.shipmentNote())
                    .param("status", ShipmentStatus.SHIPPED.name())
                    .param("providerMode", initialProviderMode.name())
                    .param("uploadStatus", WechatShippingUploadStatus.SKIPPED.name())
                    .param("shippedAt", now)
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        int updated = jdbcClient.sql("""
                        update shop_order
                        set status = :newStatus, shipped_at = :shippedAt, updated_at = :updatedAt
                        where id = :orderId and status = :expectedStatus
                        """)
                .param("newStatus", OrderStatus.SHIPPED.name())
                .param("shippedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .param("expectedStatus", OrderStatus.PAID.name())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return getForAdmin(orderId);
    }

    public OrderShipmentResponse getForAdmin(long orderId) {
        return jdbcClient.sql("""
                        select id as shipment_id, order_id, logistics_type, delivery_mode, item_desc,
                               express_company_code, express_company_name, tracking_no,
                               shipment_note, status as local_shipment_status,
                               wechat_provider_mode, wechat_upload_status,
                               wechat_error_code, wechat_error_message, retry_count,
                               shipped_at, upload_time, wechat_uploaded_at, last_attempt_at
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private OrderForShipment lockPaidOrder(long orderId) {
        OrderForShipment order = jdbcClient.sql("""
                        select id, status, receiver_phone
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderForShipment(
                        rs.getLong("id"), rs.getString("status"), rs.getString("receiver_phone")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!OrderStatus.PAID.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return order;
    }

    private NormalizedShipment normalize(AdminShipOrderRequest request, String receiverPhone) {
        if (request == null || request.logisticsType() == null || !StringUtils.hasText(request.itemDesc())) {
            throw validationFailure();
        }
        if (request.itemDesc().codePointCount(0, request.itemDesc().length()) > MAX_ITEM_DESC_CODE_POINTS) {
            throw validationFailure();
        }

        String shipmentNote = trimToEmpty(request.shipmentNote());
        if (request.logisticsType() != LogisticsType.EXPRESS) {
            if (StringUtils.hasText(request.expressCompanyCode())
                    || StringUtils.hasText(request.trackingNo())
                    || StringUtils.hasText(request.consignorContact())) {
                throw validationFailure();
            }
            return new NormalizedShipment(
                    request.logisticsType(), request.itemDesc(), null, null, null,
                    null, null, shipmentNote
            );
        }

        String submittedCode = trimToNull(request.expressCompanyCode());
        String trackingNo = trimToNull(request.trackingNo());
        if (submittedCode == null || trackingNo == null) {
            throw validationFailure();
        }
        Carrier carrier = resolveEnabledCarrier(submittedCode);
        if (!carrier.deliveryId().equals(submittedCode)) {
            throw validationFailure();
        }
        String submittedConsignorContact = trimToNull(request.consignorContact());
        String consignorContact = contactMasker.mask(submittedConsignorContact);
        if (submittedConsignorContact != null && consignorContact == null) {
            throw validationFailure();
        }
        String receiverContact = SF_DELIVERY_ID.equals(carrier.deliveryId())
                ? contactMasker.mask(receiverPhone)
                : null;
        if (SF_DELIVERY_ID.equals(carrier.deliveryId())
                && consignorContact == null
                && receiverContact == null) {
            throw validationFailure();
        }
        return new NormalizedShipment(
                LogisticsType.EXPRESS, request.itemDesc(), carrier.deliveryId(), carrier.deliveryName(),
                trackingNo, consignorContact, receiverContact, shipmentNote
        );
    }

    private Carrier resolveEnabledCarrier(String submittedCode) {
        return jdbcClient.sql("""
                        select delivery_id, delivery_name
                        from wechat_delivery_company
                        where delivery_id = :deliveryId and enabled = true
                        """)
                .param("deliveryId", submittedCode)
                .query((rs, rowNum) -> new Carrier(
                        rs.getString("delivery_id"), rs.getString("delivery_name")
                ))
                .optional()
                .orElseThrow(this::validationFailure);
    }

    private WechatProviderMode initialProviderMode() {
        if (!shippingProperties.isUploadEnabled()) {
            return WechatProviderMode.DISABLED;
        }
        try {
            WechatProviderMode mode = shippingProvider.mode();
            return mode == null ? WechatProviderMode.UNKNOWN : mode;
        } catch (RuntimeException ex) {
            return WechatProviderMode.UNKNOWN;
        }
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

    private void requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private record OrderForShipment(long orderId, String status, String receiverPhone) {
    }

    private record Carrier(String deliveryId, String deliveryName) {
    }

    private record NormalizedShipment(
            LogisticsType logisticsType,
            String itemDesc,
            String expressCompanyCode,
            String expressCompanyName,
            String trackingNo,
            String consignorContact,
            String receiverContact,
            String shipmentNote
    ) {
    }
}
