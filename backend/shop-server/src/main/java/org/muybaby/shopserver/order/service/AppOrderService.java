package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.service.AfterSaleFulfillmentPolicy;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleQueryService;
import org.muybaby.shopserver.analytics.AnalyticsEventService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationStatus;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationSummary;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatReceiptQueryStatus;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AppOrderShipmentResponse;
import org.muybaby.shopserver.logistics.dto.ShipmentItemResponse;
import org.muybaby.shopserver.logistics.provider.WechatReceiptQueryResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadRecovery;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.OrderStatusGroup;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.order.cleanup.PurgedOrderIdentityDigests;
import org.muybaby.shopserver.order.dto.AppOrderAfterSaleSummaryResponse;
import org.muybaby.shopserver.order.dto.AppOrderDetailResponse;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderReceiverUpdateRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewResponse;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.order.dto.OrderReceiverUpdateResponse;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryItemResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
import org.muybaby.shopserver.payment.OrderPaymentTimeoutScheduledEvent;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.promotion.CheckoutContext;
import org.muybaby.shopserver.promotion.CouponCandidate;
import org.muybaby.shopserver.promotion.CouponDiscountCalculator;
import org.muybaby.shopserver.promotion.DiscountResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.muybaby.shopserver.user.address.service.AppAddressService;
import org.muybaby.shopserver.user.address.service.OwnedAddress;
import org.muybaby.shopserver.user.service.AppUserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class AppOrderService {

    private static final String OPERATOR_TYPE_APP = "APP";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_ORDER_SEARCH_KEYWORD_LENGTH = 80;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int ORDER_NO_RANDOM_BYTES = 9;
    private static final int ORDER_NO_RANDOM_WIDTH = 14;
    private static final SecureRandom ORDER_NO_RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;
    private final CheckoutSelectionService checkoutSelectionService;
    private final AppAddressService appAddressService;
    private final AppAfterSaleQueryService appAfterSaleQueryService;
    private final AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy;
    private final WechatShippingUploadRecovery shippingUploadRecovery;
    private final WechatShippingProvider wechatShippingProvider;
    private final OrderStatusLogService orderStatusLogService;
    private final OrderReceiptCompletionService orderReceiptCompletionService;
    private final OrderPaymentDeadlinePolicy orderPaymentDeadlinePolicy;
    private final AppUserService appUserService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final CouponDiscountCalculator couponDiscountCalculator = new CouponDiscountCalculator();

    public AppOrderService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService,
            CheckoutSelectionService checkoutSelectionService,
            AppAddressService appAddressService,
            AppAfterSaleQueryService appAfterSaleQueryService,
            AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy,
            WechatShippingUploadRecovery shippingUploadRecovery,
            WechatShippingProvider wechatShippingProvider,
            OrderStatusLogService orderStatusLogService,
            OrderReceiptCompletionService orderReceiptCompletionService,
            OrderPaymentDeadlinePolicy orderPaymentDeadlinePolicy,
            AppUserService appUserService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
        this.checkoutSelectionService = checkoutSelectionService;
        this.appAddressService = appAddressService;
        this.appAfterSaleQueryService = appAfterSaleQueryService;
        this.afterSaleFulfillmentPolicy = afterSaleFulfillmentPolicy;
        this.shippingUploadRecovery = shippingUploadRecovery;
        this.wechatShippingProvider = wechatShippingProvider;
        this.orderStatusLogService = orderStatusLogService;
        this.orderReceiptCompletionService = orderReceiptCompletionService;
        this.orderPaymentDeadlinePolicy = orderPaymentDeadlinePolicy;
        this.appUserService = appUserService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public OrderPreviewResponse preview(AuthenticatedPrincipal principal, AppOrderPreviewRequest request) {
        Long userId = requireAppUser(principal);
        CheckoutRequest checkoutRequest = CheckoutRequest.from(request);
        CheckoutSelection selection = checkoutSelectionService.preview(userId, checkoutRequest);
        if (checkoutRequest.addressId() != null) {
            appAddressService.get(userId, checkoutRequest.addressId());
        }
        AppliedCoupon coupon = resolveCoupon(userId, checkoutRequest.userCouponId(), selection.context(), false);
        return toPreviewResponse(selection, coupon);
    }

    @Transactional
    public OrderSubmitResponse submit(AuthenticatedPrincipal principal, AppOrderSubmitRequest request) {
        Long userId = requireAppUser(principal);
        appUserService.requireEnabledUserForUpdate(userId);
        CheckoutRequest checkoutRequest = CheckoutRequest.from(request);
        checkoutSelectionService.validate(checkoutRequest);
        validateSubmitRequest(request, checkoutRequest);
        String analyticsVisitorId = AnalyticsEventService.optionalUuid(request.analyticsVisitorId());
        String analyticsSessionId = AnalyticsEventService.optionalUuid(request.analyticsSessionId());
        if ((analyticsVisitorId == null) != (analyticsSessionId == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String analyticsEntryScene = AnalyticsEventService.optionalEntryScene(request.analyticsEntryScene());

        Optional<ExistingOrder> existing = findExistingOrder(userId, request.idempotencyKey(), false);
        if (existing.isPresent()) {
            return replayExisting(existing.get(), checkoutRequest);
        }

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime paymentExpiresAt = orderPaymentDeadlinePolicy.deadlineFrom(now);
        String orderNo = nextOrderNo(now);
        Long orderId;
        try {
            orderId = insertOrderOwnership(
                    userId,
                    orderNo,
                    checkoutRequest.source(),
                    request.idempotencyKey(),
                    CheckoutRequestDigest.initialOwnershipDigest(checkoutRequest),
                    analyticsVisitorId,
                    analyticsSessionId,
                    analyticsEntryScene,
                    paymentExpiresAt,
                    now
            );
        } catch (DuplicateKeyException ex) {
            ExistingOrder winner = findExistingOrder(userId, request.idempotencyKey(), true)
                    .orElseThrow(() -> ex);
            return replayExisting(winner, checkoutRequest);
        }

        CheckoutSelection selection = checkoutSelectionService.lockForSubmit(userId, checkoutRequest);
        OwnedAddress receiver = appAddressService.requireOwnedForUpdate(userId, checkoutRequest.addressId());
        AppliedCoupon coupon = resolveCoupon(userId, checkoutRequest.userCouponId(), selection.context(), true);
        long payableAmountCent = Math.addExact(
                Math.max(selection.productAmountCent() - coupon.discountCent(), 0L),
                selection.freightCent()
        );
        String requestDigest = CheckoutRequestDigest.digest(checkoutRequest, selection.freightCent());
        updateOrderAmounts(orderId, selection, coupon, receiver, payableAmountCent, requestDigest, now);
        List<OrderItemOperatingSnapshot> operatingSnapshots = OrderAmountAllocator.allocate(
                selection.previewItems(),
                selection.unitCostCents(),
                coupon.discountCent(),
                selection.freightCent());
        long allocatedPayableAmountCent = operatingSnapshots.stream()
                .mapToLong(OrderItemOperatingSnapshot::paidAmountAllocatedCent)
                .reduce(0L, Math::addExact);
        if (allocatedPayableAmountCent != payableAmountCent) {
            throw new IllegalStateException("Order item paid allocation does not balance");
        }
        List<Long> orderItemIds = insertOrderItems(orderId, selection.previewItems(), operatingSnapshots, now);
        applyStockLocks(userId, orderId, orderItemIds, selection.previewItems(), now);
        if (coupon.userCouponId() != null) {
            lockCoupon(userId, coupon.userCouponId(), orderId, now);
        }
        if (selection.source() == CheckoutSource.CART) {
            deleteCartItems(userId, selection.selectedCartItemIds());
        }
        orderStatusLogService.record(
                orderId, "", OrderStatus.CREATED.name(), "ORDER_CREATED",
                OPERATOR_TYPE_APP, userId, "订单创建", now
        );
        eventPublisher.publishEvent(new OrderPaymentTimeoutScheduledEvent(orderId, paymentExpiresAt));

        return new OrderSubmitResponse(
                orderId,
                orderNo,
                OrderStatus.CREATED.name(),
                payableAmountCent,
                coupon.discountCent(),
                now
        );
    }

    @Transactional(readOnly = true)
    public PageResult<OrderSummaryResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status
    ) {
        return list(principal, current, size, status, OrderStatusGroup.ALL);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderSummaryResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status,
            OrderStatusGroup statusGroup
    ) {
        return list(principal, current, size, status, statusGroup, null);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderSummaryResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status,
            OrderStatusGroup statusGroup,
            String keyword
    ) {
        Long userId = requireAppUser(principal);
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        long offset = (pageCurrent - 1) * pageSize;
        String normalizedStatus = normalizeStatus(status);
        String keywordLike = normalizeOrderSearchKeyword(keyword);
        OrderStatusGroup normalizedStatusGroup = statusGroup == null ? OrderStatusGroup.ALL : statusGroup;
        if (normalizedStatus != null && normalizedStatusGroup != OrderStatusGroup.ALL) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        boolean pendingReviewsOnly = normalizedStatusGroup == OrderStatusGroup.TO_REVIEW;
        boolean allStatuses = normalizedStatusGroup == OrderStatusGroup.ALL;
        List<String> groupedStatuses = allStatuses
                ? List.of("__ALL_STATUS_GROUP__")
                : statusesForGroup(normalizedStatusGroup);

        Long total = jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where user_id = :userId
                          and app_deleted_at is null
                          and (:status is null or status = :status)
                          and (:allStatuses = true or status in (:groupedStatuses))
                          and (:keywordLike is null
                              or lower(shop_order.order_no) like :keywordLike escape '!'
                              or exists (
                                  select 1
                                  from order_item search_item
                                  where search_item.order_id = shop_order.id
                                    and lower(search_item.product_title) like :keywordLike escape '!'
                              ))
                          and (:pendingReviewsOnly = false or (
                              status = 'COMPLETED'
                              and completed_at is not null
                              and exists (
                                  select 1
                                  from order_item pending_item
                                  where pending_item.order_id = shop_order.id
                                    and not exists (
                                        select 1
                                        from product_review review
                                        where review.source_order_item_id = pending_item.id
                                    )
                                    and exists (
                                        select 1
                                        from product_spu pending_product
                                        where pending_product.id = pending_item.spu_id
                                          and pending_product.purged_at is null
                                    )
                              )
                          ))
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .param("allStatuses", allStatuses)
                .param("groupedStatuses", groupedStatuses)
                .param("keywordLike", keywordLike)
                .param("pendingReviewsOnly", pendingReviewsOnly)
                .query(Long.class)
                .single();

        List<OrderSummaryHeader> headers = jdbcClient.sql("""
                        select o.id as order_id,
                               o.order_no,
                               o.status,
                               o.product_amount_cent,
                               o.coupon_discount_cent,
                               o.freight_cent,
                               o.payable_amount_cent,
                               o.paid_amount_cent,
                               o.created_at,
                               o.completed_at
                        from shop_order o
                        where o.user_id = :userId
                          and o.app_deleted_at is null
                          and (:status is null or o.status = :status)
                          and (:allStatuses = true or o.status in (:groupedStatuses))
                          and (:keywordLike is null
                              or lower(o.order_no) like :keywordLike escape '!'
                              or exists (
                                  select 1
                                  from order_item search_item
                                  where search_item.order_id = o.id
                                    and lower(search_item.product_title) like :keywordLike escape '!'
                              ))
                          and (:pendingReviewsOnly = false or (
                              o.status = 'COMPLETED'
                              and o.completed_at is not null
                              and exists (
                                  select 1
                                  from order_item pending_item
                                  where pending_item.order_id = o.id
                                    and not exists (
                                        select 1
                                        from product_review review
                                        where review.source_order_item_id = pending_item.id
                                    )
                                    and exists (
                                        select 1
                                        from product_spu pending_product
                                        where pending_product.id = pending_item.spu_id
                                          and pending_product.purged_at is null
                                    )
                              )
                          ))
                        order by o.created_at desc, o.id desc
                        limit :limit offset :offset
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .param("allStatuses", allStatuses)
                .param("groupedStatuses", groupedStatuses)
                .param("keywordLike", keywordLike)
                .param("pendingReviewsOnly", pendingReviewsOnly)
                .param("limit", pageSize)
                .param("offset", offset)
                .query(this::mapOrderSummaryHeader)
                .list();

        if (headers.isEmpty()) {
            return PageResult.of(List.of(), total == null ? 0L : total, pageCurrent, pageSize);
        }

        List<Long> orderIds = headers.stream().map(OrderSummaryHeader::orderId).toList();
        List<OrderSummaryItemRow> itemRows = jdbcClient.sql("""
                        select oi.order_id,
                               oi.id as order_item_id,
                               oi.sku_id,
                               oi.spu_id,
                               oi.product_title,
                               oi.product_subtitle,
                               oi.main_image,
                               oi.sku_image,
                               oi.display_image,
                               oi.sku_code,
                               oi.spec_text,
                               oi.unit_price_cent,
                               oi.quantity,
                               exists (
                                   select 1
                                   from product_review review
                                   where review.source_order_item_id = oi.id
                               ) as reviewed,
                               (
                                   item_order.status = 'COMPLETED'
                                   and item_order.completed_at is not null
                                   and item_order.app_deleted_at is null
                                   and not exists (
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
                               ) as reviewable
                        from order_item oi
                        join shop_order item_order on item_order.id = oi.order_id
                        where oi.order_id in (:orderIds)
                        order by oi.order_id, oi.id
                        """)
                .param("orderIds", orderIds)
                .query(this::mapOrderSummaryItemRow)
                .list();
        Map<Long, List<OrderSummaryItemResponse>> itemsByOrderId = new HashMap<>();
        for (OrderSummaryItemRow itemRow : itemRows) {
            itemsByOrderId.computeIfAbsent(itemRow.orderId(), ignored -> new ArrayList<>())
                    .add(itemRow.item());
        }

        Map<Long, AppOrderAfterSaleSummaryResponse> latestAfterSalesByOrderId = findLatestAfterSaleSummaries(
                userId,
                orderIds
        );

        List<OrderSummaryResponse> records = headers.stream()
                .map(header -> toOrderSummary(header,
                        itemsByOrderId.getOrDefault(header.orderId(), List.of()),
                        latestAfterSalesByOrderId.get(header.orderId())))
                .toList();
        return PageResult.of(records, total == null ? 0L : total, pageCurrent, pageSize);
    }

    public AppOrderDetailResponse detail(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
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
                               payment_expires_at,
                               close_reason,
                               closed_at,
                               created_at,
                               shipped_at,
                               completed_at,
                               refunding_at,
                               refunded_at
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                          and app_deleted_at is null
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrderDetailHeader)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        shippingUploadRecovery.reconcileOrder(orderId);

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
                                   where review.source_order_item_id = order_item.id
                               ) as reviewed,
                               (
                                   not exists (
                                       select 1
                                       from product_review review
                                       where review.source_order_item_id = order_item.id
                                   )
                                   and exists (
                                       select 1
                                       from product_spu review_product
                                       where review_product.id = order_item.spu_id
                                         and review_product.purged_at is null
                                   )
                                   and exists (
                                       select 1
                                       from shop_order review_order
                                       where review_order.id = order_item.order_id
                                         and review_order.status = 'COMPLETED'
                                         and review_order.completed_at is not null
                                         and review_order.app_deleted_at is null
                                   )
                               ) as reviewable
                        from order_item
                        where order_id = :orderId
                        order by id asc
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderItem)
                .list();

        PaymentOrderSnapshot paymentOrder = findLatestPaymentOrder(orderId);
        LocalDateTime paymentExpiresAt = header.paymentExpiresAt();
        if (paymentExpiresAt == null && paymentOrder != null) {
            paymentExpiresAt = paymentOrder.expiresAt();
        }
        Long paymentRemainingSeconds = null;
        if (paymentExpiresAt != null) {
            long remainingMillis = Duration.between(
                    LocalDateTime.now(clock), paymentExpiresAt).toMillis();
            paymentRemainingSeconds = remainingMillis <= 0L
                    ? 0L
                    : (remainingMillis + 999L) / 1000L;
        }
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
        AfterSaleResponse latestAfterSale = appAfterSaleQueryService.latestForOrder(principal, orderId);
        List<Long> rebuyableOrderItemIds = findRebuyableOrderItemIds(userId, orderId);
        List<AppOrderShipmentResponse> shipments = findShipments(orderId);

        return new AppOrderDetailResponse(
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
                paymentExpiresAt,
                paymentRemainingSeconds,
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
                shipments.isEmpty() ? null : shipments.getLast(),
                shipments,
                latestAfterSale,
                rebuyableOrderItemIds,
                items
        );
    }

    private List<Long> findRebuyableOrderItemIds(Long userId, Long orderId) {
        return jdbcClient.sql("""
                        select item.id
                        from order_item item
                        join product_sku sku
                          on sku.id = item.sku_id
                         and sku.deleted_at is null
                        join product_spu product
                          on product.id = sku.spu_id
                         and product.deleted_at is null
                         and product.purged_at is null
                        join product_category category
                          on category.id = product.category_id
                        left join cart_item cart
                          on cart.user_id = :userId
                         and cart.sku_id = sku.id
                        where item.order_id = :orderId
                          and sku.status = 'ENABLED'
                          and product.status = 'ON_SALE'
                          and category.status = 'ENABLED'
                          and sku.stock_available >= item.quantity + coalesce(cart.quantity, 0)
                          and item.quantity + coalesce(cart.quantity, 0) <= 999
                        order by item.id asc
                        """)
                .param("userId", userId)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
    }

    @Transactional
    public void deleteFinished(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        DeletableOrder order = jdbcClient.sql("""
                        select id as order_id,
                               status,
                               app_deleted_at
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query((rs, rowNum) -> new DeletableOrder(
                        rs.getLong("order_id"),
                        rs.getString("status"),
                        rs.getObject("app_deleted_at", LocalDateTime.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!isAppDeletableStatus(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (order.appDeletedAt() != null) {
            return;
        }
        int updatedRows = jdbcClient.sql("""
                        update shop_order
                        set app_deleted_at = :deletedAt,
                            updated_at = :deletedAt
                        where id = :orderId
                          and user_id = :userId
                          and status in ('CLOSED', 'COMPLETED', 'REFUNDED')
                          and app_deleted_at is null
                        """)
                .param("deletedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("orderId", order.orderId())
                .param("userId", userId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    @Transactional
    public OrderReceiverUpdateResponse updateReceiver(
            AuthenticatedPrincipal principal,
            Long orderId,
            AppOrderReceiverUpdateRequest request
    ) {
        Long userId = requireAppUser(principal);
        if (request == null || request.addressId() == null || request.addressId() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ReceiverEditableOrder order = jdbcClient.sql("""
                        select id as order_id,
                               status
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                          and app_deleted_at is null
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query((rs, rowNum) -> new ReceiverEditableOrder(
                        rs.getLong("order_id"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!OrderStatus.CREATED.name().equals(order.status())
                && !OrderStatus.PAYING.name().equals(order.status())
                && !OrderStatus.PAID.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (hasActiveElectronicWaybill(order.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        OwnedAddress receiver = appAddressService.requireOwnedForUpdate(userId, request.addressId());
        LocalDateTime updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int updatedRows = jdbcClient.sql("""
                        update shop_order
                        set receiver_name = :receiverName,
                            receiver_phone = :receiverPhone,
                            receiver_address = :receiverAddress,
                            receiver_province = :receiverProvince,
                            receiver_city = :receiverCity,
                            receiver_district = :receiverDistrict,
                            receiver_detail_address = :receiverDetailAddress,
                            receiver_location_name = :receiverLocationName,
                            receiver_doorplate = :receiverDoorplate,
                            updated_at = :updatedAt
                        where id = :orderId
                          and user_id = :userId
                          and status = :status
                          and app_deleted_at is null
                        """)
                .param("receiverName", receiver.receiverName())
                .param("receiverPhone", receiver.receiverPhone())
                .param("receiverAddress", receiver.formattedAddress())
                .param("receiverProvince", receiver.province())
                .param("receiverCity", receiver.city())
                .param("receiverDistrict", receiver.district())
                .param("receiverDetailAddress", receiver.detailAddress())
                .param("receiverLocationName", receiver.locationName())
                .param("receiverDoorplate", receiver.doorplate())
                .param("updatedAt", updatedAt)
                .param("orderId", order.orderId())
                .param("userId", userId)
                .param("status", order.status())
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        orderStatusLogService.record(
                order.orderId(), order.status(), order.status(), "ORDER_RECEIVER_UPDATED",
                OPERATOR_TYPE_APP, userId, "用户修改订单收货信息", updatedAt
        );
        return new OrderReceiverUpdateResponse(
                order.orderId(),
                order.status(),
                receiver.receiverName(),
                receiver.receiverPhone(),
                receiver.formattedAddress(),
                updatedAt
        );
    }

    private boolean hasActiveElectronicWaybill(long orderId) {
        Integer activeCount = jdbcClient.sql("""
                        select count(*)
                        from order_electronic_waybill
                        where order_id = :orderId
                          and status in ('CREATING', 'CREATED', 'CANCELING', 'UNKNOWN')
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        return activeCount != null && activeCount > 0;
    }

    public OrderReceiptResponse confirmReceipt(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        ReceiptVerificationOrder verificationOrder = jdbcClient.sql("""
                        select id as order_id,
                               status,
                               completed_at,
                               payment_transaction_id
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                          and app_deleted_at is null
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query((rs, rowNum) -> new ReceiptVerificationOrder(
                        rs.getLong("order_id"),
                        rs.getString("status"),
                        rs.getObject("completed_at", LocalDateTime.class),
                        rs.getString("payment_transaction_id")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        if (OrderStatus.COMPLETED.name().equals(verificationOrder.status())) {
            return new OrderReceiptResponse(
                    verificationOrder.orderId(),
                    verificationOrder.status(),
                    verificationOrder.completedAt()
            );
        }
        if (!OrderStatus.SHIPPED.name().equals(verificationOrder.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        afterSaleFulfillmentPolicy.rejectIfBlocked(verificationOrder.orderId());

        ReceiptShipmentPolicy shipmentPolicy = findReceiptShipmentPolicy(
                verificationOrder.orderId()
        );
        if (shipmentPolicy != null && shipmentPolicy.permitsLocalFallback()) {
            return orderReceiptCompletionService.completeForUserWithLocalFallback(
                    userId, verificationOrder.orderId()
            );
        }
        if (shipmentPolicy != null && shipmentPolicy.ambiguous()) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        }

        PaymentOrderSnapshot latestPayment = findLatestPaymentOrder(verificationOrder.orderId());
        String transactionId = nonBlank(
                latestPayment == null ? null : latestPayment.transactionId(),
                verificationOrder.paymentTransactionId()
        );
        verifyWechatReceipt(transactionId);

        return orderReceiptCompletionService.completeForUser(
                userId, verificationOrder.orderId());
    }

    private ReceiptShipmentPolicy findReceiptShipmentPolicy(long orderId) {
        return jdbcClient.sql("""
                        select wechat_provider_mode, wechat_upload_status
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ReceiptShipmentPolicy(
                        providerMode(rs.getString("wechat_provider_mode")),
                        uploadStatus(rs.getString("wechat_upload_status"))
                ))
                .optional()
                .orElse(null);
    }

    private void verifyWechatReceipt(String transactionId) {
        WechatProviderMode providerMode;
        try {
            providerMode = wechatShippingProvider.mode();
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        }
        if (providerMode == WechatProviderMode.MOCK) {
            return;
        }
        if (providerMode != WechatProviderMode.REAL || !StringUtils.hasText(transactionId)) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        }

        WechatReceiptQueryResult queryResult;
        try {
            queryResult = wechatShippingProvider.queryReceiptStatus(transactionId);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        }
        if (queryResult == null) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        }
        if (queryResult.confirmed()) {
            return;
        }
        if (queryResult.status() == WechatReceiptQueryStatus.NOT_CONFIRMED) {
            throw new BusinessException(ErrorCode.WECHAT_RECEIPT_NOT_CONFIRMED);
        }
        throw new BusinessException(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
    }

    private AppliedCoupon resolveCoupon(Long userId, Long userCouponId, CheckoutContext context, boolean forUpdate) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (userCouponId != null) {
            return requireApplicableCoupon(userId, userCouponId, context, now, forUpdate);
        }

        Optional<UserCouponRow> bestCandidate = findClaimedCoupons(userId, now).stream()
                .map(candidate -> new EvaluatedCoupon(candidate, evaluateCoupon(context, candidate)))
                .filter(candidate -> Boolean.TRUE.equals(candidate.discountResult().available()))
                .sorted(Comparator
                        .comparing((EvaluatedCoupon value) -> value.discountResult().discountAmountCent(), Comparator.reverseOrder())
                        .thenComparing(value -> value.userCoupon().validEndAt())
                        .thenComparing(value -> value.userCoupon().userCouponId()))
                .map(EvaluatedCoupon::userCoupon)
                .findFirst();

        if (bestCandidate.isEmpty()) {
            return AppliedCoupon.none();
        }
        if (!forUpdate) {
            DiscountResult discountResult = evaluateCoupon(context, bestCandidate.get());
            return new AppliedCoupon(
                    bestCandidate.get().userCouponId(),
                    bestCandidate.get().name(),
                    discountResult.discountAmountCent()
            );
        }
        return requireApplicableCoupon(userId, bestCandidate.get().userCouponId(), context, now, true);
    }

    private AppliedCoupon requireApplicableCoupon(
            Long userId,
            Long userCouponId,
            CheckoutContext context,
            LocalDateTime now,
            boolean forUpdate
    ) {
        UserCouponRow coupon = findUserCoupon(userId, userCouponId, forUpdate)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));
        if (!UserCouponStatus.CLAIMED.name().equals(coupon.status())) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
        if (coupon.validStartAt().isAfter(now) || coupon.validEndAt().isBefore(now)) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
        DiscountResult discountResult = evaluateCoupon(context, coupon);
        if (!Boolean.TRUE.equals(discountResult.available())) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
        return new AppliedCoupon(coupon.userCouponId(), coupon.name(), discountResult.discountAmountCent());
    }

    private DiscountResult evaluateCoupon(CheckoutContext context, UserCouponRow coupon) {
        return couponDiscountCalculator.calculate(context, new CouponCandidate(
                coupon.userCouponId(),
                coupon.templateId(),
                coupon.name(),
                coupon.couponType(),
                coupon.discountType(),
                coupon.thresholdCent(),
                coupon.discountCent(),
                coupon.scopeType(),
                coupon.scopeValue()
        ));
    }

    private Optional<ExistingOrder> findExistingOrder(Long userId, String idempotencyKey, boolean forUpdate) {
        String sql = """
                        select id as order_id,
                               order_no,
                               status,
                               payable_amount_cent,
                               coupon_discount_cent,
                               freight_cent,
                               checkout_request_digest,
                               created_at
                        from shop_order
                        where user_id = :userId
                          and idempotency_key = :idempotencyKey
                        """ + (forUpdate ? " for update" : "");
        Optional<ExistingOrder> existing = jdbcClient.sql(sql)
                .param("userId", userId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapExistingOrder)
                .optional();
        if (existing.isEmpty() && wasPurged(userId, idempotencyKey)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return existing;
    }

    private boolean wasPurged(Long userId, String idempotencyKey) {
        return jdbcClient.sql("""
                        select count(*)
                        from purged_order_identity
                        where user_idempotency_digest = :digest
                        """)
                .param("digest", PurgedOrderIdentityDigests.userIdempotency(userId, idempotencyKey))
                .query(Long.class)
                .single() > 0L;
    }

    private OrderSubmitResponse replayExisting(ExistingOrder existing, CheckoutRequest request) {
        String freightAwareDigest = CheckoutRequestDigest.digest(request, existing.freightCent());
        if (existing.checkoutRequestDigest().equals(freightAwareDigest)) {
            return existing.response();
        }
        throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
    }

    private Long insertOrderOwnership(
            Long userId,
            String orderNo,
            CheckoutSource source,
            String idempotencyKey,
            String checkoutRequestDigest,
            String analyticsVisitorId,
            String analyticsSessionId,
            String analyticsEntryScene,
            LocalDateTime paymentExpiresAt,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into shop_order (
                            order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                            analytics_visitor_id, analytics_session_id, analytics_entry_scene,
                            payment_expires_at, created_at, updated_at
                        )
                        values (
                            :orderNo, :userId, :status, :source, :idempotencyKey, :checkoutRequestDigest,
                            :analyticsVisitorId, :analyticsSessionId, :analyticsEntryScene,
                            :paymentExpiresAt, :createdAt, :updatedAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("orderNo", orderNo)
                        .addValue("userId", userId)
                        .addValue("status", OrderStatus.CREATED.name())
                        .addValue("source", source.name())
                        .addValue("idempotencyKey", idempotencyKey)
                        .addValue("checkoutRequestDigest", checkoutRequestDigest)
                        .addValue("analyticsVisitorId", analyticsVisitorId)
                        .addValue("analyticsSessionId", analyticsSessionId)
                        .addValue("analyticsEntryScene", analyticsEntryScene)
                        .addValue("paymentExpiresAt", paymentExpiresAt)
                        .addValue("createdAt", now)
                        .addValue("updatedAt", now),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void updateOrderAmounts(
            Long orderId,
            CheckoutSelection selection,
            AppliedCoupon coupon,
            OwnedAddress receiver,
            long payableAmountCent,
            String checkoutRequestDigest,
            LocalDateTime now
    ) {
        int updatedRows = jdbcClient.sql("""
                        update shop_order
                        set product_original_amount_cent = :productOriginalAmountCent,
                            product_amount_cent = :productAmountCent,
                            user_coupon_id = :userCouponId,
                            coupon_name = :couponName,
                            coupon_discount_cent = :couponDiscountCent,
                            freight_cent = :freightCent,
                            payable_amount_cent = :payableAmountCent,
                            checkout_request_digest = :checkoutRequestDigest,
                            receiver_name = :receiverName,
                            receiver_phone = :receiverPhone,
                            receiver_address = :receiverAddress,
                            receiver_province = :receiverProvince,
                            receiver_city = :receiverCity,
                            receiver_district = :receiverDistrict,
                            receiver_detail_address = :receiverDetailAddress,
                            receiver_location_name = :receiverLocationName,
                            receiver_doorplate = :receiverDoorplate,
                            updated_at = :updatedAt
                        where id = :orderId
                        """)
                .param("productOriginalAmountCent", selection.productOriginalAmountCent())
                .param("productAmountCent", selection.productAmountCent())
                .param("userCouponId", coupon.userCouponId())
                .param("couponName", defaultString(coupon.couponName()))
                .param("couponDiscountCent", coupon.discountCent())
                .param("freightCent", selection.freightCent())
                .param("payableAmountCent", payableAmountCent)
                .param("checkoutRequestDigest", checkoutRequestDigest)
                .param("receiverName", receiver.receiverName())
                .param("receiverPhone", receiver.receiverPhone())
                .param("receiverAddress", receiver.formattedAddress())
                .param("receiverProvince", receiver.province())
                .param("receiverCity", receiver.city())
                .param("receiverDistrict", receiver.district())
                .param("receiverDetailAddress", receiver.detailAddress())
                .param("receiverLocationName", receiver.locationName())
                .param("receiverDoorplate", receiver.doorplate())
                .param("updatedAt", now)
                .param("orderId", orderId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private List<Long> insertOrderItems(
            Long orderId,
            List<OrderPreviewItemResponse> items,
            List<OrderItemOperatingSnapshot> operatingSnapshots,
            LocalDateTime now
    ) {
        if (items.size() != operatingSnapshots.size()) {
            throw new IllegalArgumentException("Operating snapshots must align with order items");
        }
        List<Long> orderItemIds = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            OrderPreviewItemResponse item = items.get(index);
            OrderItemOperatingSnapshot snapshot = operatingSnapshots.get(index);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            namedParameterJdbcTemplate.update("""
                            insert into order_item (
                                order_id, sku_id, spu_id, product_title, product_subtitle, main_image,
                                main_image_file_id, sku_image, sku_image_file_id, display_image, display_image_file_id,
                                sku_code, spec_text, original_price_cent,
                                unit_price_cent, retail_unit_price_cent, wholesale_tier_min_quantity,
                                quantity, line_original_amount_cent, line_amount_cent,
                                unit_cost_cent, line_cost_cent, coupon_discount_allocated_cent,
                                freight_allocated_cent, paid_amount_allocated_cent, created_at
                            )
                            values (
                                :orderId, :skuId, :spuId, :productTitle, :productSubtitle, :mainImage,
                                :mainImageFileId, :skuImage, :skuImageFileId, :displayImage, :displayImageFileId, :skuCode, :specText, :originalPriceCent,
                                :unitPriceCent, :retailUnitPriceCent, :wholesaleTierMinQuantity,
                                :quantity, :lineOriginalAmountCent, :lineAmountCent,
                                :unitCostCent, :lineCostCent, :couponDiscountAllocatedCent,
                                :freightAllocatedCent, :paidAmountAllocatedCent, :createdAt
                            )
                            """,
                    new MapSqlParameterSource()
                            .addValue("orderId", orderId)
                            .addValue("skuId", item.skuId())
                            .addValue("spuId", item.spuId())
                            .addValue("productTitle", item.productTitle())
                            .addValue("productSubtitle", defaultString(item.productSubtitle()))
                            .addValue("mainImage", defaultString(item.mainImage()))
                            .addValue("mainImageFileId", item.mainImageFileId())
                            .addValue("skuImage", defaultString(item.skuImage()))
                            .addValue("skuImageFileId", item.skuImageFileId())
                            .addValue("displayImage", defaultString(item.displayImage()))
                            .addValue("displayImageFileId", item.displayImageFileId())
                            .addValue("skuCode", item.skuCode())
                            .addValue("specText", defaultString(item.specText()))
                            .addValue("originalPriceCent", item.originalPriceCent())
                            .addValue("unitPriceCent", item.unitPriceCent())
                            .addValue("retailUnitPriceCent", item.retailUnitPriceCent())
                            .addValue("wholesaleTierMinQuantity", item.wholesaleTierMinQuantity())
                            .addValue("quantity", item.quantity())
                            .addValue("lineOriginalAmountCent", item.lineOriginalAmountCent())
                            .addValue("lineAmountCent", item.lineAmountCent())
                            .addValue("unitCostCent", snapshot.unitCostCent())
                            .addValue("lineCostCent", snapshot.lineCostCent())
                            .addValue("couponDiscountAllocatedCent", snapshot.couponDiscountAllocatedCent())
                            .addValue("freightAllocatedCent", snapshot.freightAllocatedCent())
                            .addValue("paidAmountAllocatedCent", snapshot.paidAmountAllocatedCent())
                            .addValue("createdAt", now),
                    keyHolder,
                    new String[]{"id"});
            Long orderItemId = requireGeneratedId(keyHolder);
            addOrderItemFileUsages(orderItemId, item);
            orderItemIds.add(orderItemId);
        }
        return orderItemIds;
    }

    private void addOrderItemFileUsages(Long orderItemId, OrderPreviewItemResponse item) {
        addProtectedSnapshotUsage(orderItemId, item.productTitle(), item.mainImageFileId(), item.mainImage(), 1);
        addProtectedSnapshotUsage(orderItemId, item.productTitle(), item.skuImageFileId(), item.skuImage(), 2);
        addProtectedSnapshotUsage(orderItemId, item.productTitle(), item.displayImageFileId(), item.displayImage(), 3);
    }

    private void addProtectedSnapshotUsage(
            Long orderItemId,
            String ownerLabel,
            Long fileId,
            String snapshotUrl,
            int sortOrder
    ) {
        if (fileId == null) {
            return;
        }
        storageUsageService.addProtectedUsage(
                fileId,
                StorageFileUsageType.ORDER_ITEM_SNAPSHOT,
                StorageUsageOwnerType.ORDER_ITEM,
                orderItemId,
                ownerLabel,
                defaultString(snapshotUrl),
                sortOrder
        );
    }

    private void applyStockLocks(
            Long userId,
            Long orderId,
            List<Long> orderItemIds,
            List<OrderPreviewItemResponse> items,
            LocalDateTime now
    ) {
        for (int index = 0; index < items.size(); index++) {
            OrderPreviewItemResponse item = items.get(index);
            int quantityBefore = jdbcClient.sql("""
                            select stock_available
                            from product_sku
                            where id = :skuId
                            for update
                            """)
                    .param("skuId", item.skuId())
                    .query(Integer.class)
                    .single();
            int quantityAfter = quantityBefore - item.quantity();
            int updatedRows = jdbcClient.sql("""
                            update product_sku
                            set stock_available = stock_available - :quantity,
                                updated_at = :updatedAt
                            where id = :skuId
                              and stock_available >= :quantity
                            """)
                    .param("quantity", item.quantity())
                    .param("updatedAt", now)
                    .param("skuId", item.skuId())
                    .update();
            if (updatedRows != 1) {
                throw new BusinessException(ErrorCode.STOCK_SHORTAGE);
            }
            jdbcClient.sql("""
                            insert into stock_lock (
                                order_id, order_item_id, sku_id, quantity, status, locked_at, created_at, updated_at
                            )
                            values (
                                :orderId, :orderItemId, :skuId, :quantity, :status, :lockedAt, :createdAt, :updatedAt
                            )
                            """)
                    .param("orderId", orderId)
                    .param("orderItemId", orderItemIds.get(index))
                    .param("skuId", item.skuId())
                    .param("quantity", item.quantity())
                    .param("status", StockLockStatus.LOCKED.name())
                    .param("lockedAt", now)
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
            insertStockLog(
                    orderId,
                    item.skuId(),
                    StockChangeType.ORDER_LOCK.name(),
                    quantityBefore,
                    -item.quantity(),
                    quantityAfter,
                    "Order submit " + orderId,
                    OPERATOR_TYPE_APP,
                    userId
            );
        }
    }

    private void lockCoupon(Long userId, Long userCouponId, Long orderId, LocalDateTime now) {
        int updatedRows = jdbcClient.sql("""
                        update user_coupon
                        set status = :status,
                            locked_order_id = :orderId,
                            locked_at = :lockedAt,
                            updated_at = :updatedAt
                        where id = :userCouponId
                          and user_id = :userId
                          and status = :expectedStatus
                          and valid_start_at <= :now
                          and valid_end_at >= :now
                        """)
                .param("status", UserCouponStatus.LOCKED.name())
                .param("orderId", orderId)
                .param("lockedAt", now)
                .param("updatedAt", now)
                .param("userCouponId", userCouponId)
                .param("userId", userId)
                .param("expectedStatus", UserCouponStatus.CLAIMED.name())
                .param("now", now)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
    }

    private void deleteCartItems(Long userId, List<Long> cartItemIds) {
        int deletedRows = namedParameterJdbcTemplate.update("""
                        delete from cart_item
                        where user_id = :userId
                          and id in (:cartItemIds)
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("cartItemIds", cartItemIds));
        if (deletedRows != cartItemIds.size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    private Optional<UserCouponRow> findUserCoupon(Long userId, Long userCouponId, boolean forUpdate) {
        String sql = """
                select id as user_coupon_id,
                       template_id,
                       template_name,
                       coupon_type,
                       discount_type,
                       threshold_cent,
                       discount_cent,
                       scope_type,
                       scope_value,
                       valid_start_at,
                       valid_end_at,
                       status
                from user_coupon
                where user_id = :userId
                  and id = :userCouponId
                """ + (forUpdate ? "\nfor update" : "");
        List<UserCouponRow> rows = jdbcClient.sql(sql)
                .param("userId", userId)
                .param("userCouponId", userCouponId)
                .query(this::mapUserCoupon)
                .list();
        return rows.stream().findFirst();
    }

    private List<UserCouponRow> findClaimedCoupons(Long userId, LocalDateTime now) {
        return jdbcClient.sql("""
                        select id as user_coupon_id,
                               template_id,
                               template_name,
                               coupon_type,
                               discount_type,
                               threshold_cent,
                               discount_cent,
                               scope_type,
                               scope_value,
                               valid_start_at,
                               valid_end_at,
                               status
                        from user_coupon
                        where user_id = :userId
                          and status = :status
                          and valid_start_at <= :now
                          and valid_end_at >= :now
                        order by valid_end_at asc, id asc
                        """)
                .param("userId", userId)
                .param("status", UserCouponStatus.CLAIMED.name())
                .param("now", now)
                .query(this::mapUserCoupon)
                .list();
    }

    private OrderPreviewResponse toPreviewResponse(CheckoutSelection selection, AppliedCoupon coupon) {
        long payableAmountCent = Math.addExact(
                Math.max(selection.productAmountCent() - coupon.discountCent(), 0L),
                selection.freightCent()
        );
        return new OrderPreviewResponse(
                selection.previewItems(),
                selection.productOriginalAmountCent(),
                selection.productAmountCent(),
                coupon.userCouponId(),
                defaultString(coupon.couponName()),
                coupon.discountCent(),
                selection.freightCent(),
                payableAmountCent
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

    private ExistingOrder mapExistingOrder(ResultSet rs, int rowNum) throws SQLException {
        return new ExistingOrder(new OrderSubmitResponse(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("coupon_discount_cent"),
                rs.getObject("created_at", LocalDateTime.class)
        ), rs.getString("checkout_request_digest"), rs.getLong("freight_cent"));
    }

    private OrderSummaryHeader mapOrderSummaryHeader(ResultSet rs, int rowNum) throws SQLException {
        return new OrderSummaryHeader(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("product_amount_cent"),
                rs.getLong("coupon_discount_cent"),
                rs.getLong("freight_cent"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class)
        );
    }

    private OrderSummaryItemRow mapOrderSummaryItemRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderSummaryItemRow(
                rs.getLong("order_id"),
                new OrderSummaryItemResponse(
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
                        rs.getLong("unit_price_cent"),
                        rs.getInt("quantity"),
                        rs.getBoolean("reviewed"),
                        rs.getBoolean("reviewable")
                )
        );
    }

    private OrderSummaryResponse toOrderSummary(
            OrderSummaryHeader header,
            List<OrderSummaryItemResponse> items,
            AppOrderAfterSaleSummaryResponse latestAfterSale
    ) {
        int itemCount = items.stream().mapToInt(OrderSummaryItemResponse::quantity).sum();
        int pendingReviewCount = (int) items.stream()
                .filter(OrderSummaryItemResponse::reviewable)
                .count();
        String productTitle = items.isEmpty() ? "" : items.getFirst().productTitle();
        return new OrderSummaryResponse(
                header.orderId(),
                header.orderNo(),
                header.status(),
                header.productAmountCent(),
                header.couponDiscountCent(),
                header.freightCent(),
                header.payableAmountCent(),
                header.paidAmountCent(),
                productTitle,
                itemCount,
                items,
                pendingReviewCount,
                latestAfterSale,
                header.createdAt()
        );
    }

    private Map<Long, AppOrderAfterSaleSummaryResponse> findLatestAfterSaleSummaries(
            Long userId,
            List<Long> orderIds
    ) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                        select asr.order_id,
                               asr.after_sale_type,
                               asr.status,
                               asr.requested_amount_cent,
                               asr.approved_amount_cent,
                               (
                                   select ro.refund_amount_cent
                                   from refund_order ro
                                   where ro.after_sale_id = asr.id
                                   order by ro.requested_at desc, ro.id desc
                                   limit 1
                               ) as refund_amount_cent
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        where o.user_id = :userId
                          and asr.order_id in (:orderIds)
                          and not exists (
                              select 1
                              from after_sale_request newer
                              where newer.order_id = asr.order_id
                                and (
                                    newer.created_at > asr.created_at
                                    or (newer.created_at = asr.created_at and newer.id > asr.id)
                                )
                          )
                        """)
                .param("userId", userId)
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("order_id"),
                        new AppOrderAfterSaleSummaryResponse(
                                rs.getString("after_sale_type"),
                                rs.getString("status"),
                                rs.getLong("requested_amount_cent"),
                                rs.getObject("approved_amount_cent", Long.class),
                                rs.getObject("refund_amount_cent", Long.class)
                        )
                ))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
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
                rs.getObject("payment_expires_at", LocalDateTime.class),
                rs.getString("close_reason"),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("refunding_at", LocalDateTime.class),
                rs.getObject("refunded_at", LocalDateTime.class)
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
                               paid_at,
                               expires_at
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
                        rs.getObject("paid_at", LocalDateTime.class),
                        rs.getObject("expires_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
    }

    private String nonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private List<AppOrderShipmentResponse> findShipments(Long orderId) {
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
                               sender_address,
                               shipment_source,
                               electronic_waybill_id,
                               status as local_shipment_status,
                               wechat_provider_mode,
                               wechat_upload_status,
                               shipped_at,
                               upload_time,
                               wechat_uploaded_at,
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

    private AppOrderShipmentResponse mapShipment(ResultSet rs, int rowNum) throws SQLException {
        WechatProviderMode providerMode = providerMode(rs.getString("wechat_provider_mode"));
        WechatShippingUploadStatus uploadStatus = uploadStatus(rs.getString("wechat_upload_status"));
        LogisticsType logisticsType = LogisticsType.fromValue(rs.getInt("logistics_type"));
        WaybillRegistrationKind registrationKind = registrationKind(
                rs.getString("waybill_registration_kind")
        );
        WaybillRegistrationStatus registrationStatus = WaybillRegistrationSummary.effectiveStatus(
                registrationStatus(rs.getString("waybill_registration_status")),
                rs.getString("electronic_waybill_mode")
        );
        long shipmentId = rs.getLong("shipment_id");
        return new AppOrderShipmentResponse(
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
                rs.getString("local_shipment_status"),
                providerMode,
                uploadStatus,
                uploadMessage(providerMode, uploadStatus),
                WaybillRegistrationSummary.trackingSupported(
                        logisticsType,
                        rs.getString("express_company_code"),
                        rs.getString("tracking_no"),
                        registrationStatus
                ),
                registrationKind,
                registrationStatus,
                WaybillRegistrationSummary.safeMessage(registrationStatus),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getString("upload_time"),
                rs.getObject("wechat_uploaded_at", LocalDateTime.class),
                findShipmentItems(shipmentId),
                rs.getString("sender_address")
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

    private String uploadMessage(WechatProviderMode mode, WechatShippingUploadStatus status) {
        if (mode == WechatProviderMode.REAL && status == WechatShippingUploadStatus.UPLOADED) {
            return "WeChat has accepted the shipping information";
        }
        return switch (status) {
            case PENDING -> "Shipping information is queued for platform upload";
            case SKIPPED -> "Shipping information is pending platform upload";
            case UPLOADING -> "Shipping information is being uploaded";
            case FAILED -> "Shipping information has not been uploaded yet";
            case UNAVAILABLE -> "Platform shipping service is currently unavailable";
            case UNKNOWN -> "Platform upload status is being confirmed";
            case UPLOADED -> "Shipping information was saved locally";
        };
    }

    private record ReceiptShipmentPolicy(
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

        boolean ambiguous() {
            return providerMode == WechatProviderMode.REAL
                    && (uploadStatus == WechatShippingUploadStatus.UPLOADING
                    || uploadStatus == WechatShippingUploadStatus.UNKNOWN);
        }
    }

    private UserCouponRow mapUserCoupon(ResultSet rs, int rowNum) throws SQLException {
        return new UserCouponRow(
                rs.getLong("user_coupon_id"),
                rs.getLong("template_id"),
                rs.getString("template_name"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class),
                rs.getString("status")
        );
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private void validateSubmitRequest(AppOrderSubmitRequest request, CheckoutRequest checkoutRequest) {
        if (request == null
                || checkoutRequest.addressId() == null
                || !StringUtils.hasText(request.idempotencyKey())
                || request.idempotencyKey().length() > 80) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private long normalizeCurrent(Long current) {
        if (current == null) {
            return 1L;
        }
        if (current < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return current;
    }

    private long normalizeSize(Long size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim() : null;
    }

    private String normalizeOrderSearchKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String normalized = keyword.trim().replaceAll("\\s+", " ");
        if (normalized.length() > MAX_ORDER_SEARCH_KEYWORD_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String escaped = normalized
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    private List<String> statusesForGroup(OrderStatusGroup statusGroup) {
        return switch (statusGroup) {
            case UNPAID -> List.of(OrderStatus.CREATED.name(), OrderStatus.PAYING.name());
            case TO_SHIP -> List.of(OrderStatus.PAID.name(), OrderStatus.PARTIALLY_SHIPPED.name());
            case TO_RECEIVE -> List.of(OrderStatus.SHIPPED.name());
            case TO_REVIEW, COMPLETED -> List.of(OrderStatus.COMPLETED.name());
            case CANCELLED -> List.of(OrderStatus.CLOSED.name());
            case ALL -> List.of("__ALL_STATUS_GROUP__");
        };
    }

    private boolean isAppDeletableStatus(String status) {
        return OrderStatus.CLOSED.name().equals(status)
                || OrderStatus.COMPLETED.name().equals(status)
                || OrderStatus.REFUNDED.name().equals(status);
    }

    static String nextOrderNo(LocalDateTime now) {
        byte[] randomBytes = new byte[ORDER_NO_RANDOM_BYTES];
        ORDER_NO_RANDOM.nextBytes(randomBytes);
        String randomSuffix = new BigInteger(1, randomBytes).toString(Character.MAX_RADIX)
                .toUpperCase(Locale.ROOT);
        return "ORD"
                + now.format(ORDER_NO_TIME_FORMATTER)
                + "0".repeat(ORDER_NO_RANDOM_WIDTH - randomSuffix.length())
                + randomSuffix;
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        return Optional.ofNullable(keyHolder.getKey())
                .map(Number::longValue)
                .orElseThrow(() -> new IllegalStateException("Generated key missing"));
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record UserCouponRow(
            Long userCouponId,
            Long templateId,
            String name,
            String couponType,
            String discountType,
            Long thresholdCent,
            Long discountCent,
            String scopeType,
            String scopeValue,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt,
            String status
    ) {
    }

    private record ExistingOrder(
            OrderSubmitResponse response,
            String checkoutRequestDigest,
            long freightCent
    ) {
    }

    private record EvaluatedCoupon(UserCouponRow userCoupon, DiscountResult discountResult) {
    }

    private record AppliedCoupon(Long userCouponId, String couponName, long discountCent) {
        private static AppliedCoupon none() {
            return new AppliedCoupon(null, "", 0L);
        }
    }

    private record OrderSummaryHeader(
            Long orderId,
            String orderNo,
            String status,
            Long productAmountCent,
            Long couponDiscountCent,
            Long freightCent,
            Long payableAmountCent,
            Long paidAmountCent,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }

    private record OrderSummaryItemRow(Long orderId, OrderSummaryItemResponse item) {
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
            LocalDateTime paymentExpiresAt,
            String closeReason,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime shippedAt,
            LocalDateTime completedAt,
            LocalDateTime refundingAt,
            LocalDateTime refundedAt
    ) {
    }

    private record PaymentOrderSnapshot(
            String outTradeNo,
            String transactionId,
            String status,
            LocalDateTime paidAt,
            LocalDateTime expiresAt
    ) {
    }

    private record DeletableOrder(Long orderId, String status, LocalDateTime appDeletedAt) {
    }

    private record ReceiverEditableOrder(Long orderId, String status) {
    }

    private record ReceiptVerificationOrder(
            Long orderId,
            String status,
            LocalDateTime completedAt,
            String paymentTransactionId
    ) {
    }
}
