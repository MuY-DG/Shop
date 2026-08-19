package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.aftersale.service.AfterSaleFulfillmentPolicy;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.ShipmentStatus;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.dto.ShipmentItemRequest;
import org.muybaby.shopserver.logistics.dto.ShipmentItemResponse;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationStatus;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationSummary;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LocalShipmentService {

    private static final int MAX_ITEM_DESC_CODE_POINTS = 120;
    private static final String SF_DELIVERY_ID = "SF";
    private static final String SHIPMENT_SELECT = """
            select id as shipment_id, order_id, package_no, final_shipment,
                   logistics_type, delivery_mode, item_desc,
                   express_company_code, express_company_name, tracking_no,
                   shipment_source, electronic_waybill_id,
                   shipment_note, status as local_shipment_status,
                   wechat_provider_mode, wechat_upload_status,
                   wechat_error_code, wechat_error_message, retry_count,
                   shipped_at, upload_time, wechat_uploaded_at, last_attempt_at,
                   (
                       select mode
                       from order_electronic_waybill electronic_waybill
                       where electronic_waybill.id = order_shipment.electronic_waybill_id
                   ) as electronic_waybill_mode,
                   (
                       select registration_kind
                       from shipment_waybill_registration registration
                       where registration.shipment_id = order_shipment.id
                   ) as waybill_registration_kind,
                   (
                       select status
                       from shipment_waybill_registration registration
                       where registration.shipment_id = order_shipment.id
                   ) as waybill_registration_status
            from order_shipment
            """;

    private final JdbcClient jdbcClient;
    private final WechatShippingRuntimeSettingService runtimeSettingService;
    private final WechatShippingProvider shippingProvider;
    private final ShipmentContactMasker contactMasker;
    private final AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy;
    private final TransactionTemplate transactionTemplate;
    private final OrderStatusLogService orderStatusLogService;

    public LocalShipmentService(
            JdbcClient jdbcClient,
            WechatShippingRuntimeSettingService runtimeSettingService,
            WechatShippingProvider shippingProvider,
            ShipmentContactMasker contactMasker,
            AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy,
            PlatformTransactionManager transactionManager,
            OrderStatusLogService orderStatusLogService
    ) {
        this.jdbcClient = jdbcClient;
        this.runtimeSettingService = runtimeSettingService;
        this.shippingProvider = shippingProvider;
        this.contactMasker = contactMasker;
        this.afterSaleFulfillmentPolicy = afterSaleFulfillmentPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.orderStatusLogService = orderStatusLogService;
    }

    public OrderShipmentResponse create(
            AuthenticatedPrincipal principal,
            long orderId,
            AdminShipOrderRequest request
    ) {
        Long adminUserId = requireAdmin(principal);
        boolean uploadEnabled = runtimeSettingService.uploadEnabledFailClosed();
        WechatProviderMode initialProviderMode = initialProviderMode(uploadEnabled);
        return transactionTemplate.execute(status -> createInTransaction(
                orderId, request, uploadEnabled, initialProviderMode, adminUserId
        ));
    }

    private OrderShipmentResponse createInTransaction(
            long orderId,
            AdminShipOrderRequest request,
            boolean uploadEnabled,
            WechatProviderMode initialProviderMode,
            Long adminUserId
    ) {
        OrderForShipment order = lockShippableOrder(orderId);
        rejectIfActiveElectronicWaybill(orderId);
        afterSaleFulfillmentPolicy.rejectIfBlocked(orderId);
        List<ShipmentAllocation> allocations = normalizeAllocations(orderId, request.items());
        int packageNo = nextPackageNo(orderId);
        boolean finalShipment = remainingQuantity(orderId) == allocatedQuantity(allocations);
        DeliveryMode deliveryMode = packageNo == 1 && finalShipment
                ? DeliveryMode.UNIFIED
                : DeliveryMode.SPLIT;
        NormalizedShipment shipment = normalize(request, order.receiverPhone());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        try {
            jdbcClient.sql("""
                            insert into order_shipment(
                                order_id, package_no, final_shipment,
                                logistics_type, delivery_mode, item_desc,
                                express_company_code, express_company_name, tracking_no,
                                consignor_contact, receiver_contact, shipment_note,
                                shipment_source, electronic_waybill_id,
                                status, wechat_provider_mode, wechat_upload_status,
                                wechat_error_code, wechat_error_message, retry_count,
                                wechat_upload_next_action_at,
                                shipped_at, created_at, updated_at)
                            values (
                                :orderId, :packageNo, :finalShipment,
                                :logisticsType, :deliveryMode, :itemDesc,
                                :expressCompanyCode, :expressCompanyName, :trackingNo,
                                :consignorContact, :receiverContact, :shipmentNote,
                                :shipmentSource, null,
                                :status, :providerMode, :uploadStatus,
                                '', '', 0, :nextActionAt,
                                :shippedAt, :createdAt, :updatedAt)
                            """)
                    .param("orderId", orderId)
                    .param("packageNo", packageNo)
                    .param("finalShipment", finalShipment)
                    .param("logisticsType", shipment.logisticsType().value())
                    .param("deliveryMode", deliveryMode.value())
                    .param("itemDesc", shipment.itemDesc())
                    .param("expressCompanyCode", shipment.expressCompanyCode())
                    .param("expressCompanyName", shipment.expressCompanyName())
                    .param("trackingNo", shipment.trackingNo())
                    .param("consignorContact", shipment.consignorContact())
                    .param("receiverContact", shipment.receiverContact())
                    .param("shipmentNote", shipment.shipmentNote())
                    .param("shipmentSource", ShipmentSource.MANUAL.name())
                    .param("status", ShipmentStatus.SHIPPED.name())
                    .param("providerMode", initialProviderMode.name())
                    .param("uploadStatus", initialUploadStatus(uploadEnabled).name())
                    .param("nextActionAt", initialNextActionAt(uploadEnabled, now))
                    .param("shippedAt", now)
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        long shipmentId = requireShipmentId(orderId, packageNo);
        insertShipmentItems(shipmentId, allocations, now);

        OrderStatus nextStatus = finalShipment ? OrderStatus.SHIPPED : OrderStatus.PARTIALLY_SHIPPED;
        int updated = jdbcClient.sql("""
                        update shop_order
                        set status = :newStatus,
                            shipped_at = case when :finalShipment then :shippedAt else shipped_at end,
                            updated_at = :updatedAt
                        where id = :orderId and status = :expectedStatus
                        """)
                .param("newStatus", nextStatus.name())
                .param("finalShipment", finalShipment)
                .param("shippedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .param("expectedStatus", order.status())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        orderStatusLogService.record(
                orderId, order.status(), nextStatus.name(),
                finalShipment ? "ORDER_SHIPPED" : "ORDER_PARTIALLY_SHIPPED",
                "ADMIN", adminUserId,
                finalShipment ? "订单全部发货" : "订单部分发货（包裹 " + packageNo + "）", now
        );
        return getForAdmin(orderId, shipmentId);
    }

    public OrderShipmentResponse confirmElectronicWaybill(
            AuthenticatedPrincipal principal,
            long orderId,
            long waybillRecordId
    ) {
        Long adminUserId = requireAdmin(principal);
        boolean uploadEnabled = runtimeSettingService.uploadEnabledFailClosed();
        WechatProviderMode initialProviderMode = initialProviderMode(uploadEnabled);
        return transactionTemplate.execute(status -> confirmElectronicWaybillInTransaction(
                orderId, waybillRecordId, uploadEnabled, initialProviderMode, adminUserId
        ));
    }

    private OrderShipmentResponse confirmElectronicWaybillInTransaction(
            long orderId,
            long waybillRecordId,
            boolean uploadEnabled,
            WechatProviderMode initialProviderMode,
            long adminUserId
    ) {
        OrderForConfirmation order = lockOrderForConfirmation(orderId);
        ElectronicWaybillForConfirmation waybill = lockElectronicWaybill(orderId, waybillRecordId);
        if ((OrderStatus.PARTIALLY_SHIPPED.name().equals(order.status())
                || OrderStatus.SHIPPED.name().equals(order.status()))
                && "CONFIRMED".equals(waybill.status())
                && shipmentLinkedToWaybill(orderId, waybillRecordId)) {
            return getForAdmin(orderId, requireShipmentIdForWaybill(orderId, waybillRecordId));
        }
        if (!isShippableStatus(order.status())
                || !"CREATED".equals(waybill.status())
                || !"NONE".equals(waybill.pendingOperation())
                || !StringUtils.hasText(waybill.deliveryId())
                || !StringUtils.hasText(waybill.deliveryName())
                || !StringUtils.hasText(waybill.waybillId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        afterSaleFulfillmentPolicy.rejectIfBlocked(orderId);
        List<ShipmentAllocation> allocations = loadWaybillAllocations(orderId, waybillRecordId);
        int packageNo = nextPackageNo(orderId);
        boolean finalShipment = remainingQuantity(orderId) == allocatedQuantity(allocations);
        DeliveryMode deliveryMode = packageNo == 1 && finalShipment
                ? DeliveryMode.UNIFIED
                : DeliveryMode.SPLIT;

        String itemDesc = buildItemDescription(allocations);
        String consignorContact = contactMasker.mask(waybill.senderMobile());
        String receiverContact = SF_DELIVERY_ID.equals(waybill.deliveryId())
                ? contactMasker.mask(waybill.receiverPhone())
                : null;
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        try {
            jdbcClient.sql("""
                            insert into order_shipment(
                                order_id, package_no, final_shipment,
                                logistics_type, delivery_mode, item_desc,
                                express_company_code, express_company_name, tracking_no,
                                consignor_contact, receiver_contact, shipment_note,
                                shipment_source, electronic_waybill_id,
                                status, wechat_provider_mode, wechat_upload_status,
                                wechat_error_code, wechat_error_message, retry_count,
                                wechat_upload_next_action_at,
                                shipped_at, created_at, updated_at)
                            values (
                                :orderId, :packageNo, :finalShipment,
                                :logisticsType, :deliveryMode, :itemDesc,
                                :expressCompanyCode, :expressCompanyName, :trackingNo,
                                :consignorContact, :receiverContact, '',
                                :shipmentSource, :electronicWaybillId,
                                :status, :providerMode, :uploadStatus,
                                '', '', 0, :nextActionAt,
                                :shippedAt, :createdAt, :updatedAt)
                            """)
                    .param("orderId", orderId)
                    .param("packageNo", packageNo)
                    .param("finalShipment", finalShipment)
                    .param("logisticsType", LogisticsType.EXPRESS.value())
                    .param("deliveryMode", deliveryMode.value())
                    .param("itemDesc", itemDesc)
                    .param("expressCompanyCode", waybill.deliveryId())
                    .param("expressCompanyName", waybill.deliveryName())
                    .param("trackingNo", waybill.waybillId())
                    .param("consignorContact", consignorContact)
                    .param("receiverContact", receiverContact)
                    .param("shipmentSource", ShipmentSource.WECHAT_WAYBILL.name())
                    .param("electronicWaybillId", waybillRecordId)
                    .param("status", ShipmentStatus.SHIPPED.name())
                    .param("providerMode", initialProviderMode.name())
                    .param("uploadStatus", initialUploadStatus(uploadEnabled).name())
                    .param("nextActionAt", initialNextActionAt(uploadEnabled, now))
                    .param("shippedAt", now)
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        long shipmentId = requireShipmentId(orderId, packageNo);
        insertShipmentItems(shipmentId, allocations, now);

        int waybillUpdated = jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = 'CONFIRMED', pending_operation = 'NONE',
                            confirmed_by = :confirmedBy, confirmed_at = :confirmedAt,
                            updated_at = :updatedAt
                        where id = :waybillRecordId
                          and order_id = :orderId
                          and status = 'CREATED'
                          and pending_operation = 'NONE'
                        """)
                .param("confirmedBy", adminUserId)
                .param("confirmedAt", now)
                .param("updatedAt", now)
                .param("waybillRecordId", waybillRecordId)
                .param("orderId", orderId)
                .update();
        OrderStatus nextStatus = finalShipment ? OrderStatus.SHIPPED : OrderStatus.PARTIALLY_SHIPPED;
        int orderUpdated = jdbcClient.sql("""
                        update shop_order
                        set status = :newStatus,
                            shipped_at = case when :finalShipment then :shippedAt else shipped_at end,
                            updated_at = :updatedAt
                        where id = :orderId and status = :expectedStatus
                        """)
                .param("newStatus", nextStatus.name())
                .param("finalShipment", finalShipment)
                .param("shippedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .param("expectedStatus", order.status())
                .update();
        if (waybillUpdated != 1 || orderUpdated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        orderStatusLogService.record(
                orderId, order.status(), nextStatus.name(),
                finalShipment ? "ORDER_SHIPPED" : "ORDER_PARTIALLY_SHIPPED",
                "ADMIN", adminUserId,
                finalShipment ? "订单全部发货" : "订单部分发货（包裹 " + packageNo + "）", now
        );
        return getForAdmin(orderId, shipmentId);
    }

    public OrderShipmentResponse getForAdmin(long orderId) {
        return jdbcClient.sql(SHIPMENT_SELECT + """
                        where order_id = :orderId
                        order by package_no desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    public OrderShipmentResponse getForAdmin(long orderId, long shipmentId) {
        return jdbcClient.sql(SHIPMENT_SELECT + """
                        where order_id = :orderId and id = :shipmentId
                        """)
                .param("orderId", orderId)
                .param("shipmentId", shipmentId)
                .query(this::mapShipment)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    public List<OrderShipmentResponse> listForAdmin(long orderId) {
        return jdbcClient.sql(SHIPMENT_SELECT + """
                        where order_id = :orderId
                        order by package_no, id
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .list();
    }

    private OrderForShipment lockShippableOrder(long orderId) {
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
        if (!isShippableStatus(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return order;
    }

    private OrderForConfirmation lockOrderForConfirmation(long orderId) {
        return jdbcClient.sql("""
                        select id, status
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderForConfirmation(
                        rs.getLong("id"), rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private ElectronicWaybillForConfirmation lockElectronicWaybill(
            long orderId,
            long waybillRecordId
    ) {
        return jdbcClient.sql("""
                        select id, status, pending_operation,
                               delivery_id, delivery_name, waybill_id,
                               sender_mobile, receiver_phone
                        from order_electronic_waybill
                        where id = :waybillRecordId and order_id = :orderId
                        for update
                        """)
                .param("waybillRecordId", waybillRecordId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ElectronicWaybillForConfirmation(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("pending_operation"),
                        rs.getString("delivery_id"),
                        rs.getString("delivery_name"),
                        rs.getString("waybill_id"),
                        rs.getString("sender_mobile"),
                        rs.getString("receiver_phone")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private boolean shipmentLinkedToWaybill(long orderId, long waybillRecordId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from order_shipment
                        where order_id = :orderId
                          and shipment_source = :shipmentSource
                          and electronic_waybill_id = :electronicWaybillId
                        """)
                .param("orderId", orderId)
                .param("shipmentSource", ShipmentSource.WECHAT_WAYBILL.name())
                .param("electronicWaybillId", waybillRecordId)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }

    private long requireShipmentIdForWaybill(long orderId, long waybillRecordId) {
        return jdbcClient.sql("""
                        select id from order_shipment
                        where order_id = :orderId
                          and shipment_source = :shipmentSource
                          and electronic_waybill_id = :electronicWaybillId
                        """)
                .param("orderId", orderId)
                .param("shipmentSource", ShipmentSource.WECHAT_WAYBILL.name())
                .param("electronicWaybillId", waybillRecordId)
                .query(Long.class)
                .single();
    }

    private List<ShipmentAllocation> normalizeAllocations(
            long orderId,
            List<ShipmentItemRequest> requested
    ) {
        Map<Long, RemainingItem> remaining = remainingItems(orderId);
        if (remaining.isEmpty()) {
            Integer itemCount = jdbcClient.sql("select count(*) from order_item where order_id = :orderId")
                    .param("orderId", orderId)
                    .query(Integer.class)
                    .single();
            if ((requested == null || requested.isEmpty()) && itemCount != null && itemCount == 0) {
                return List.of();
            }
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (requested == null || requested.isEmpty()) {
            return remaining.values().stream()
                    .map(item -> new ShipmentAllocation(
                            item.orderItemId(), item.productTitle(), item.specText(), item.remainingQuantity()
                    ))
                    .toList();
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (ShipmentItemRequest item : requested) {
            if (item == null || item.orderItemId() == null || item.quantity() == null
                    || item.orderItemId() < 1 || item.quantity() < 1
                    || quantities.putIfAbsent(item.orderItemId(), item.quantity()) != null) {
                throw validationFailure();
            }
        }
        List<ShipmentAllocation> allocations = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            RemainingItem item = remaining.get(entry.getKey());
            if (item == null || entry.getValue() > item.remainingQuantity()) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            allocations.add(new ShipmentAllocation(
                    item.orderItemId(), item.productTitle(), item.specText(), entry.getValue()
            ));
        }
        return List.copyOf(allocations);
    }

    private List<ShipmentAllocation> loadWaybillAllocations(long orderId, long waybillRecordId) {
        Map<Long, RemainingItem> remaining = remainingItems(orderId);
        List<ShipmentAllocation> allocations = jdbcClient.sql("""
                        select item.id as order_item_id, item.product_title, item.spec_text,
                               waybill_item.quantity
                        from order_electronic_waybill_item waybill_item
                        join order_electronic_waybill waybill
                          on waybill.id = waybill_item.electronic_waybill_id
                        join order_item item on item.id = waybill_item.order_item_id
                        where waybill.id = :waybillRecordId
                          and waybill.order_id = :orderId
                          and item.order_id = :orderId
                        order by item.id
                        """)
                .param("waybillRecordId", waybillRecordId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ShipmentAllocation(
                        rs.getLong("order_item_id"),
                        rs.getString("product_title"),
                        rs.getString("spec_text"),
                        rs.getInt("quantity")
                ))
                .list();
        if (allocations.isEmpty() || allocations.stream().anyMatch(allocation -> {
            RemainingItem item = remaining.get(allocation.orderItemId());
            return item == null || allocation.quantity() > item.remainingQuantity();
        })) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return allocations;
    }

    private Map<Long, RemainingItem> remainingItems(long orderId) {
        Map<Long, RemainingItem> remaining = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select item.id as order_item_id, item.product_title, item.spec_text,
                               item.quantity - item.refunded_quantity
                                 - coalesce(sum(shipment_item.quantity), 0) as remaining_quantity
                        from order_item item
                        left join order_shipment_item shipment_item
                          on shipment_item.order_item_id = item.id
                        where item.order_id = :orderId
                        group by item.id, item.product_title, item.spec_text,
                                 item.quantity, item.refunded_quantity
                        having item.quantity - item.refunded_quantity
                                 - coalesce(sum(shipment_item.quantity), 0) > 0
                        order by item.id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new RemainingItem(
                        rs.getLong("order_item_id"),
                        rs.getString("product_title"),
                        rs.getString("spec_text"),
                        rs.getInt("remaining_quantity")
                ))
                .list()
                .forEach(item -> remaining.put(item.orderItemId(), item));
        return remaining;
    }

    private int remainingQuantity(long orderId) {
        return remainingItems(orderId).values().stream()
                .mapToInt(RemainingItem::remainingQuantity)
                .sum();
    }

    private int allocatedQuantity(List<ShipmentAllocation> allocations) {
        return allocations.stream().mapToInt(ShipmentAllocation::quantity).sum();
    }

    private String buildItemDescription(List<ShipmentAllocation> items) {
        String joined = String.join("; ", items.stream()
                .map(item -> item.productTitle().trim() + " x" + item.quantity())
                .toList());
        int codePoints = joined.codePointCount(0, joined.length());
        if (codePoints <= MAX_ITEM_DESC_CODE_POINTS) {
            return joined;
        }
        int end = joined.offsetByCodePoints(0, MAX_ITEM_DESC_CODE_POINTS);
        return joined.substring(0, end);
    }

    private int nextPackageNo(long orderId) {
        Integer next = jdbcClient.sql("""
                        select coalesce(max(package_no), 0) + 1
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        return next == null ? 1 : next;
    }

    private long requireShipmentId(long orderId, int packageNo) {
        return jdbcClient.sql("""
                        select id from order_shipment
                        where order_id = :orderId and package_no = :packageNo
                        """)
                .param("orderId", orderId)
                .param("packageNo", packageNo)
                .query(Long.class)
                .single();
    }

    private void insertShipmentItems(
            long shipmentId,
            List<ShipmentAllocation> allocations,
            LocalDateTime now
    ) {
        for (ShipmentAllocation allocation : allocations) {
            jdbcClient.sql("""
                            insert into order_shipment_item(
                                shipment_id, order_item_id, quantity, created_at
                            ) values (:shipmentId, :orderItemId, :quantity, :createdAt)
                            """)
                    .param("shipmentId", shipmentId)
                    .param("orderItemId", allocation.orderItemId())
                    .param("quantity", allocation.quantity())
                    .param("createdAt", now)
                    .update();
        }
    }

    private void rejectIfActiveElectronicWaybill(long orderId) {
        Integer activeCount = jdbcClient.sql("""
                        select count(*)
                        from order_electronic_waybill
                        where order_id = :orderId
                          and status in ('CREATING', 'CREATED', 'CANCELING', 'UNKNOWN')
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
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

    private WechatProviderMode initialProviderMode(boolean uploadEnabled) {
        if (!uploadEnabled) {
            return WechatProviderMode.DISABLED;
        }
        try {
            WechatProviderMode mode = shippingProvider.mode();
            return mode == null ? WechatProviderMode.UNKNOWN : mode;
        } catch (RuntimeException ex) {
            return WechatProviderMode.UNKNOWN;
        }
    }

    private WechatShippingUploadStatus initialUploadStatus(boolean uploadEnabled) {
        return uploadEnabled
                ? WechatShippingUploadStatus.PENDING
                : WechatShippingUploadStatus.SKIPPED;
    }

    private LocalDateTime initialNextActionAt(boolean uploadEnabled, LocalDateTime now) {
        return uploadEnabled ? now : null;
    }

    private OrderShipmentResponse mapShipment(ResultSet rs, int rowNum) throws SQLException {
        long shipmentId = rs.getLong("shipment_id");
        LogisticsType logisticsType = LogisticsType.fromValue(rs.getInt("logistics_type"));
        WaybillRegistrationKind registrationKind = registrationKind(
                rs.getString("waybill_registration_kind")
        );
        WaybillRegistrationStatus registrationStatus = WaybillRegistrationSummary.effectiveStatus(
                registrationStatus(rs.getString("waybill_registration_status")),
                rs.getString("electronic_waybill_mode")
        );
        return new OrderShipmentResponse(
                shipmentId,
                rs.getLong("order_id"),
                rs.getInt("package_no"),
                rs.getBoolean("final_shipment"),
                logisticsType,
                DeliveryMode.fromValue(rs.getInt("delivery_mode")),
                rs.getString("item_desc"),
                rs.getString("express_company_code"),
                rs.getString("express_company_name"),
                rs.getString("tracking_no"),
                shipmentSource(rs.getString("shipment_source")),
                rs.getObject("electronic_waybill_id", Long.class),
                blankToNull(rs.getString("shipment_note")),
                rs.getString("local_shipment_status"),
                providerMode(rs.getString("wechat_provider_mode")),
                uploadStatus(rs.getString("wechat_upload_status")),
                blankToNull(rs.getString("wechat_error_code")),
                blankToNull(rs.getString("wechat_error_message")),
                WaybillRegistrationSummary.trackingSupported(
                        logisticsType,
                        rs.getString("express_company_code"),
                        rs.getString("tracking_no"),
                        registrationStatus
                ),
                registrationKind,
                registrationStatus,
                WaybillRegistrationSummary.safeMessage(registrationStatus),
                rs.getInt("retry_count"),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getString("upload_time"),
                rs.getObject("wechat_uploaded_at", LocalDateTime.class),
                rs.getObject("last_attempt_at", LocalDateTime.class),
                findShipmentItems(shipmentId)
        );
    }

    private List<ShipmentItemResponse> findShipmentItems(long shipmentId) {
        return jdbcClient.sql("""
                        select item.id as order_item_id, item.product_title, item.spec_text,
                               shipment_item.quantity
                        from order_shipment_item shipment_item
                        join order_item item on item.id = shipment_item.order_item_id
                        where shipment_item.shipment_id = :shipmentId
                        order by item.id
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new ShipmentItemResponse(
                        rs.getLong("order_item_id"),
                        rs.getString("product_title"),
                        rs.getString("spec_text"),
                        rs.getInt("quantity")
                ))
                .list();
    }

    private boolean isShippableStatus(String status) {
        return OrderStatus.PAID.name().equals(status)
                || OrderStatus.PARTIALLY_SHIPPED.name().equals(status);
    }

    private ShipmentSource shipmentSource(String value) {
        try {
            return ShipmentSource.valueOf(value);
        } catch (RuntimeException ex) {
            return ShipmentSource.MANUAL;
        }
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

    private WaybillRegistrationKind registrationKind(String value) {
        try {
            return value == null ? null : WaybillRegistrationKind.valueOf(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private WaybillRegistrationStatus registrationStatus(String value) {
        try {
            return value == null ? null : WaybillRegistrationStatus.valueOf(value);
        } catch (RuntimeException ex) {
            return WaybillRegistrationStatus.UNKNOWN;
        }
    }

    private Long requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
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

    private record OrderForConfirmation(long orderId, String status) {
    }

    private record ElectronicWaybillForConfirmation(
            long id,
            String status,
            String pendingOperation,
            String deliveryId,
            String deliveryName,
            String waybillId,
            String senderMobile,
            String receiverPhone
    ) {
    }

    private record RemainingItem(
            long orderItemId,
            String productTitle,
            String specText,
            int remainingQuantity
    ) {
    }

    private record ShipmentAllocation(
            long orderItemId,
            String productTitle,
            String specText,
            int quantity
    ) {
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
