package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.aftersale.service.AfterSaleFulfillmentPolicy;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.dto.ShipmentItemResponse;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadRecovery;
import org.muybaby.shopserver.logistics.waybill.ElectronicWaybillService;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationStatus;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationSummary;
import org.muybaby.shopserver.order.AdminOrderStatusGroup;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.dto.AdminOrderAfterSaleSummaryResponse;
import org.muybaby.shopserver.order.dto.AdminOrderQueryRequest;
import org.muybaby.shopserver.order.dto.AdminOrderSummaryResponse;
import org.muybaby.shopserver.order.dto.AdminOrderStatusCountsResponse;
import org.muybaby.shopserver.order.dto.OrderDetailResponse;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.muybaby.shopserver.order.dto.OrderStatusLogResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.muybaby.shopserver.order.repository.OrderItemFulfillmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminOrderService {

    private static final String CLOSE_REASON_ADMIN = "ADMIN_CLOSE";
    private static final String OPERATOR_TYPE_ADMIN = "ADMIN";

    private final JdbcClient jdbcClient;
    private final OrderItemFulfillmentRepository fulfillment;
    private final OrderCloseService orderCloseService;
    private final WechatShippingUploadRecovery shippingUploadRecovery;
    private final AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy;
    private final ElectronicWaybillService electronicWaybillService;

    public AdminOrderService(
            JdbcClient jdbcClient,
            OrderCloseService orderCloseService,
            WechatShippingUploadRecovery shippingUploadRecovery,
            AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy,
            ElectronicWaybillService electronicWaybillService,
            OrderItemFulfillmentRepository fulfillment
    ) {
        this.jdbcClient = jdbcClient;
        this.fulfillment = fulfillment;
        this.orderCloseService = orderCloseService;
        this.shippingUploadRecovery = shippingUploadRecovery;
        this.afterSaleFulfillmentPolicy = afterSaleFulfillmentPolicy;
        this.electronicWaybillService = electronicWaybillService;
    }

    public PageResult<AdminOrderSummaryResponse> page(AuthenticatedPrincipal principal, AdminOrderQueryRequest query) {
        requireAdminUser(principal);
        AdminOrderQueryRequest normalizedQuery = normalizedQuery(query);
        OrderQueryFilters filters = normalizeFilters(normalizedQuery, true);
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from shop_order o
                        left join app_user u on u.id = o.user_id
                        where o.status in (:statuses)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                          and (:userId is null or o.user_id = :userId)
                          and (:userPhone is null or u.phone_number = :userPhone)
                          and (:userNicknameLike is null or lower(u.nickname) like lower(:userNicknameLike))
                          and (:receiverNameLike is null or o.receiver_name like :receiverNameLike)
                          and (:receiverPhoneLike is null or o.receiver_phone like :receiverPhoneLike)
                          and (:createdStart is null or o.created_at >= :createdStart)
                          and (:createdEnd is null or o.created_at <= :createdEnd)
                          and (:trackingNoLike is null or exists (
                              select 1 from order_shipment os
                              where os.order_id = o.id and os.tracking_no like :trackingNoLike
                          ))
                        """)
                .param("statuses", filters.statuses())
                .param("orderNoLike", filters.orderNoLike())
                .param("userId", filters.userId())
                .param("userPhone", filters.userPhone())
                .param("userNicknameLike", filters.userNicknameLike())
                .param("receiverNameLike", filters.receiverNameLike())
                .param("receiverPhoneLike", filters.receiverPhoneLike())
                .param("createdStart", filters.createdStart())
                .param("createdEnd", filters.createdEnd())
                .param("trackingNoLike", filters.trackingNoLike())
                .query(Long.class)
                .single();

        List<AdminOrderSummaryResponse> records = jdbcClient.sql("""
                        select o.id as order_id,
                               o.order_no,
                               o.status,
                               u.nickname as user_nickname,
                               o.product_amount_cent,
                               o.coupon_discount_cent,
                               o.freight_cent,
                               o.payable_amount_cent,
                               o.paid_amount_cent,
                               o.receiver_name,
                               o.receiver_phone,
                               coalesce(first_item.product_title, '') as product_title,
                               coalesce(first_item.product_subtitle, '') as product_subtitle,
                               coalesce(first_item.main_image, '') as main_image,
                               coalesce(first_item.sku_image, '') as sku_image,
                               coalesce(first_item.display_image, '') as display_image,
                               coalesce(first_item.spec_text, '') as spec_text,
                               coalesce(first_item.quantity, 0) as first_item_quantity,
                               coalesce(item_summary.item_count, 0) as item_count,
                               active_asr.id as active_after_sale_id,
                               active_asr.after_sale_no as active_after_sale_no,
                               active_asr.after_sale_type as active_after_sale_type,
                               active_asr.status as active_after_sale_status,
                               active_asr.requested_amount_cent as active_after_sale_amount_cent,
                               active_asr.created_at as active_after_sale_created_at,
                               o.created_at
                        from shop_order o
                        left join app_user u on u.id = o.user_id
                        left join order_item first_item on first_item.id = (
                            select min(oi.id) from order_item oi where oi.order_id = o.id
                        )
                        left join (
                            select order_id, sum(quantity) as item_count
                            from order_item
                            group by order_id
                        ) item_summary on item_summary.order_id = o.id
                        left join after_sale_request active_asr on active_asr.id = (
                            select asr.id
                            from after_sale_request asr
                            where asr.order_id = o.id
                              and asr.status in (:blockingAfterSaleStatuses)
                            order by asr.created_at desc, asr.id desc
                            limit 1
                        )
                        where o.status in (:statuses)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                          and (:userId is null or o.user_id = :userId)
                          and (:userPhone is null or u.phone_number = :userPhone)
                          and (:userNicknameLike is null or lower(u.nickname) like lower(:userNicknameLike))
                          and (:receiverNameLike is null or o.receiver_name like :receiverNameLike)
                          and (:receiverPhoneLike is null or o.receiver_phone like :receiverPhoneLike)
                          and (:createdStart is null or o.created_at >= :createdStart)
                          and (:createdEnd is null or o.created_at <= :createdEnd)
                          and (:trackingNoLike is null or exists (
                              select 1 from order_shipment os
                              where os.order_id = o.id and os.tracking_no like :trackingNoLike
                          ))
                        order by o.created_at desc, o.id desc
                        limit :limit offset :offset
                        """)
                .param("statuses", filters.statuses())
                .param("orderNoLike", filters.orderNoLike())
                .param("userId", filters.userId())
                .param("userPhone", filters.userPhone())
                .param("userNicknameLike", filters.userNicknameLike())
                .param("receiverNameLike", filters.receiverNameLike())
                .param("receiverPhoneLike", filters.receiverPhoneLike())
                .param("createdStart", filters.createdStart())
                .param("createdEnd", filters.createdEnd())
                .param("trackingNoLike", filters.trackingNoLike())
                .param("blockingAfterSaleStatuses", afterSaleFulfillmentPolicy.blockingStatuses())
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapOrderSummary)
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public AdminOrderStatusCountsResponse statusCounts(
            AuthenticatedPrincipal principal,
            AdminOrderQueryRequest query
    ) {
        requireAdminUser(principal);
        OrderQueryFilters filters = normalizeFilters(normalizedQuery(query), false);
        Map<String, Long> counts = new HashMap<>();
        jdbcClient.sql("""
                        select o.status, count(*) as status_count
                        from shop_order o
                        left join app_user u on u.id = o.user_id
                        where (:orderNoLike is null or o.order_no like :orderNoLike)
                          and (:userId is null or o.user_id = :userId)
                          and (:userPhone is null or u.phone_number = :userPhone)
                          and (:userNicknameLike is null or lower(u.nickname) like lower(:userNicknameLike))
                          and (:receiverNameLike is null or o.receiver_name like :receiverNameLike)
                          and (:receiverPhoneLike is null or o.receiver_phone like :receiverPhoneLike)
                          and (:createdStart is null or o.created_at >= :createdStart)
                          and (:createdEnd is null or o.created_at <= :createdEnd)
                          and (:trackingNoLike is null or exists (
                              select 1 from order_shipment os
                              where os.order_id = o.id and os.tracking_no like :trackingNoLike
                          ))
                        group by o.status
                        """)
                .param("orderNoLike", filters.orderNoLike())
                .param("userId", filters.userId())
                .param("userPhone", filters.userPhone())
                .param("userNicknameLike", filters.userNicknameLike())
                .param("receiverNameLike", filters.receiverNameLike())
                .param("receiverPhoneLike", filters.receiverPhoneLike())
                .param("createdStart", filters.createdStart())
                .param("createdEnd", filters.createdEnd())
                .param("trackingNoLike", filters.trackingNoLike())
                .query((rs, rowNum) -> new StatusCountRow(rs.getString("status"), rs.getLong("status_count")))
                .list()
                .forEach(row -> counts.put(row.status(), row.count()));

        return new AdminOrderStatusCountsResponse(
                countForGroup(counts, AdminOrderStatusGroup.ALL),
                countForGroup(counts, AdminOrderStatusGroup.UNPAID),
                countForGroup(counts, AdminOrderStatusGroup.TO_SHIP),
                countForGroup(counts, AdminOrderStatusGroup.TO_RECEIVE),
                countForGroup(counts, AdminOrderStatusGroup.COMPLETED),
                countForGroup(counts, AdminOrderStatusGroup.CLOSED),
                countForGroup(counts, AdminOrderStatusGroup.REFUNDING),
                countForGroup(counts, AdminOrderStatusGroup.REFUNDED)
        );
    }

    public OrderDetailResponse detail(AuthenticatedPrincipal principal, Long orderId) {
        requireAdminUser(principal);
        shippingUploadRecovery.reconcileOrder(orderId);
        OrderDetailHeader header = jdbcClient.sql("""
                        select o.id as order_id,
                               o.order_no,
                               o.status,
                               o.source,
                               o.user_id,
                               u.nickname as user_nickname,
                               case when u.phone_authorized = true then u.phone_number else null end as user_phone,
                               o.product_original_amount_cent,
                               o.product_amount_cent,
                               o.user_coupon_id,
                               o.coupon_name,
                               o.coupon_discount_cent,
                               o.freight_cent,
                               o.payable_amount_cent,
                               o.paid_amount_cent,
                               coalesce((
                                   select sum(ro.refund_amount_cent)
                                   from refund_order ro
                                   where ro.order_id = o.id and ro.status = 'SUCCESS'
                               ), 0) as refunded_amount_cent,
                               o.receiver_name,
                               o.receiver_phone,
                               o.receiver_address,
                               o.payment_transaction_id,
                               o.merchant_trade_no,
                               o.paid_at,
                               o.close_reason,
                               o.closed_at,
                               o.created_at,
                               o.shipped_at,
                               o.completed_at,
                               o.refunding_at,
                               o.refunded_at
                        from shop_order o
                        left join app_user u on u.id = o.user_id
                        where o.id = :orderId
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
                               retail_unit_price_cent,
                               wholesale_tier_min_quantity,
                               quantity,
                               line_original_amount_cent,
                               line_amount_cent,
                               exists (
                                   select 1
                                   from product_review review
                                   where review.source_order_item_id = oi.id
                               ) as reviewed,
                               (
                                   not exists (
                                       select 1
                                       from product_review review
                                       where review.source_order_item_id = oi.id
                                   )
                                   and exists (
                                       select 1
                                       from product_spu review_product
                                       where review_product.id = oi.spu_id
                                         and review_product.purged_at is null
                                   )
                                   and exists (
                                       select 1
                                       from shop_order review_order
                                       where review_order.id = oi.order_id
                                         and review_order.status = 'COMPLETED'
                                         and review_order.completed_at is not null
                                         and review_order.app_deleted_at is null
                                   )
                               ) as reviewable
                        from order_item oi
                        where oi.order_id = :orderId
                        order by oi.id asc
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
        AdminOrderAfterSaleSummaryResponse activeAfterSale = afterSaleFulfillmentPolicy.findBlocking(orderId)
                .map(this::toAfterSaleSummary)
                .orElse(null);
        boolean canShip = isShippableStatus(header.status()) && activeAfterSale == null;
        List<OrderShipmentResponse> shipments = findShipments(orderId);

        return new OrderDetailResponse(
                header.orderId(),
                header.orderNo(),
                header.status(),
                header.source(),
                header.userId(),
                header.userNickname(),
                header.userPhone(),
                header.productOriginalAmountCent(),
                header.productAmountCent(),
                header.userCouponId(),
                header.couponName(),
                header.couponDiscountCent(),
                header.freightCent(),
                header.payableAmountCent(),
                header.paidAmountCent(),
                items.stream().mapToInt(OrderItemResponse::quantity).sum(),
                header.refundedAmountCent(),
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
                header.shippedAt(),
                header.completedAt(),
                header.refundingAt(),
                header.refundedAt(),
                canShip,
                activeAfterSale,
                shipments.isEmpty() ? null : shipments.getLast(),
                shipments,
                findRemainingShipmentItems(orderId),
                electronicWaybillService.latestSummary(orderId),
                items
        );
    }

    public List<OrderStatusLogResponse> statusLogs(AuthenticatedPrincipal principal, Long orderId) {
        requireAdminUser(principal);
        Integer exists = jdbcClient.sql("select count(*) from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        if (exists == null || exists == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return jdbcClient.sql("""
                        select id, order_id, after_sale_id, from_status, to_status, event_type,
                               operator_type, operator_id, description, created_at
                        from order_status_log
                        where order_id = :orderId
                        order by created_at asc, id asc
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderStatusLogResponse(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getObject("after_sale_id", Long.class),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("event_type"),
                        rs.getString("operator_type"),
                        rs.getObject("operator_id", Long.class),
                        rs.getString("description"),
                        rs.getObject("created_at", LocalDateTime.class)
                ))
                .list();
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

    private AdminOrderSummaryResponse mapOrderSummary(ResultSet rs, int rowNum) throws SQLException {
        String status = rs.getString("status");
        AdminOrderAfterSaleSummaryResponse activeAfterSale = mapActiveAfterSale(rs);
        return new AdminOrderSummaryResponse(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                status,
                rs.getString("user_nickname"),
                rs.getLong("product_amount_cent"),
                rs.getLong("coupon_discount_cent"),
                rs.getLong("freight_cent"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getString("receiver_name"),
                rs.getString("receiver_phone"),
                rs.getString("product_title"),
                rs.getString("product_subtitle"),
                rs.getString("main_image"),
                rs.getString("sku_image"),
                rs.getString("display_image"),
                rs.getString("spec_text"),
                rs.getInt("first_item_quantity"),
                rs.getInt("item_count"),
                isShippableStatus(status) && activeAfterSale == null,
                activeAfterSale,
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private AdminOrderAfterSaleSummaryResponse mapActiveAfterSale(ResultSet rs) throws SQLException {
        Long afterSaleId = rs.getObject("active_after_sale_id", Long.class);
        if (afterSaleId == null) {
            return null;
        }
        return new AdminOrderAfterSaleSummaryResponse(
                afterSaleId,
                rs.getString("active_after_sale_no"),
                rs.getString("active_after_sale_type"),
                rs.getString("active_after_sale_status"),
                rs.getLong("active_after_sale_amount_cent"),
                rs.getObject("active_after_sale_created_at", LocalDateTime.class)
        );
    }

    private AdminOrderAfterSaleSummaryResponse toAfterSaleSummary(
            AfterSaleFulfillmentPolicy.BlockingAfterSale afterSale
    ) {
        return new AdminOrderAfterSaleSummaryResponse(
                afterSale.afterSaleId(),
                afterSale.afterSaleNo(),
                afterSale.afterSaleType(),
                afterSale.status(),
                afterSale.requestedAmountCent(),
                afterSale.createdAt()
        );
    }

    private OrderDetailHeader mapOrderDetailHeader(ResultSet rs, int rowNum) throws SQLException {
        return new OrderDetailHeader(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getLong("user_id"),
                rs.getString("user_nickname"),
                rs.getString("user_phone"),
                rs.getLong("product_original_amount_cent"),
                rs.getLong("product_amount_cent"),
                rs.getObject("user_coupon_id", Long.class),
                rs.getString("coupon_name"),
                rs.getLong("coupon_discount_cent"),
                rs.getLong("freight_cent"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getLong("refunded_amount_cent"),
                rs.getString("receiver_name"),
                rs.getString("receiver_phone"),
                rs.getString("receiver_address"),
                rs.getString("payment_transaction_id"),
                rs.getString("merchant_trade_no"),
                rs.getObject("paid_at", LocalDateTime.class),
                rs.getString("close_reason"),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("refunding_at", LocalDateTime.class),
                rs.getObject("refunded_at", LocalDateTime.class)
        );
    }

    private AdminOrderQueryRequest normalizedQuery(AdminOrderQueryRequest query) {
        return query == null
                ? new AdminOrderQueryRequest(
                        null, null, null, null, null, null,
                        null, null, null, null, null, null
                )
                : query;
    }

    private OrderQueryFilters normalizeFilters(AdminOrderQueryRequest query, boolean includeStatus) {
        List<String> statuses = Arrays.stream(OrderStatus.values()).map(Enum::name).toList();
        if (includeStatus && StringUtils.hasText(query.status())) {
            try {
                statuses = List.of(OrderStatus.valueOf(query.status().trim()).name());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } else if (includeStatus && StringUtils.hasText(query.statusGroup())) {
            try {
                statuses = AdminOrderStatusGroup.valueOf(query.statusGroup().trim()).statuses();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }

        Long userId = null;
        String userPhone = null;
        String userNicknameLike = null;
        if (StringUtils.hasText(query.userKeyword())) {
            String keyword = query.userKeyword().trim();
            if ("USER_ID".equals(query.userSearchType())) {
                try {
                    userId = Long.valueOf(keyword);
                } catch (NumberFormatException ex) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            } else if ("USER_PHONE".equals(query.userSearchType())) {
                userPhone = keyword;
            } else if ("USER_NAME".equals(query.userSearchType())) {
                userNicknameLike = like(keyword);
            } else {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        LocalDateTime createdStart = query.createdStartUtc();
        LocalDateTime createdEnd = query.createdEndUtc();
        if (createdStart != null && createdEnd != null && createdStart.isAfter(createdEnd)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new OrderQueryFilters(
                like(query.orderNo()),
                statuses,
                userId,
                userPhone,
                userNicknameLike,
                like(query.receiverName()),
                like(query.receiverPhone()),
                createdStart,
                createdEnd,
                like(query.trackingNo())
        );
    }

    private String like(String value) {
        return StringUtils.hasText(value) ? "%" + value.trim() + "%" : null;
    }

    private long countForGroup(Map<String, Long> counts, AdminOrderStatusGroup group) {
        return group.statuses().stream().mapToLong(status -> counts.getOrDefault(status, 0L)).sum();
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
                rs.getLong("retail_unit_price_cent"),
                rs.getObject("wholesale_tier_min_quantity", Integer.class),
                rs.getInt("quantity"),
                rs.getLong("line_original_amount_cent"),
                rs.getLong("line_amount_cent"),
                rs.getBoolean("reviewed"),
                rs.getBoolean("reviewable")
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

    private List<OrderShipmentResponse> findShipments(Long orderId) {
        return jdbcClient.sql("""
                        select id as shipment_id,
                               order_id,
                               package_no,
                               final_shipment,
                               logistics_type,
                               delivery_mode,
                               item_desc,
                               express_company_code,
                               express_company_name,
                               tracking_no,
                               shipment_source,
                               electronic_waybill_id,
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
                               last_attempt_at,
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
                        where order_id = :orderId
                        order by package_no, id
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .list();
    }

    private OrderShipmentResponse mapShipment(ResultSet rs, int rowNum) throws SQLException {
        LogisticsType logisticsType = LogisticsType.fromValue(rs.getInt("logistics_type"));
        WaybillRegistrationKind registrationKind = registrationKind(
                rs.getString("waybill_registration_kind")
        );
        WaybillRegistrationStatus registrationStatus = WaybillRegistrationSummary.effectiveStatus(
                registrationStatus(rs.getString("waybill_registration_status")),
                rs.getString("electronic_waybill_mode")
        );
        long shipmentId = rs.getLong("shipment_id");
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

    private List<ShipmentItemResponse> findRemainingShipmentItems(long orderId) {
        return fulfillment.items(orderId).stream().filter(item -> item.remainingQuantity() > 0)
                .map(item -> new ShipmentItemResponse(item.orderItemId(), item.title(), item.specText(), item.remainingQuantity()))
                .toList();
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

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private record OrderDetailHeader(
            Long orderId,
            String orderNo,
            String status,
            String source,
            Long userId,
            String userNickname,
            String userPhone,
            Long productOriginalAmountCent,
            Long productAmountCent,
            Long userCouponId,
            String couponName,
            Long couponDiscountCent,
            Long freightCent,
            Long payableAmountCent,
            Long paidAmountCent,
            Long refundedAmountCent,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String paymentTransactionId,
            String merchantTradeNo,
            LocalDateTime paidAt,
            String closeReason,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime shippedAt,
            LocalDateTime completedAt,
            LocalDateTime refundingAt,
            LocalDateTime refundedAt
    ) {
    }

    private record OrderQueryFilters(
            String orderNoLike,
            List<String> statuses,
            Long userId,
            String userPhone,
            String userNicknameLike,
            String receiverNameLike,
            String receiverPhoneLike,
            LocalDateTime createdStart,
            LocalDateTime createdEnd,
            String trackingNoLike
    ) {
    }

    private record StatusCountRow(String status, Long count) {
    }

    private record PaymentOrderSnapshot(
            String outTradeNo,
            String transactionId,
            String status,
            LocalDateTime paidAt
    ) {
    }
}
