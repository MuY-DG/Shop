package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AppOrderShipmentResponse;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadRecovery;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.OrderStatusGroup;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.order.dto.AppOrderDetailResponse;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewResponse;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AppOrderService {

    private static final String OPERATOR_TYPE_APP = "APP";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong ORDER_NO_SEQUENCE = new AtomicLong();

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;
    private final CheckoutSelectionService checkoutSelectionService;
    private final AppAddressService appAddressService;
    private final AppAfterSaleService appAfterSaleService;
    private final WechatShippingUploadRecovery shippingUploadRecovery;
    private final OrderStatusLogService orderStatusLogService;
    private final CouponDiscountCalculator couponDiscountCalculator = new CouponDiscountCalculator();

    public AppOrderService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService,
            CheckoutSelectionService checkoutSelectionService,
            AppAddressService appAddressService,
            AppAfterSaleService appAfterSaleService,
            WechatShippingUploadRecovery shippingUploadRecovery,
            OrderStatusLogService orderStatusLogService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
        this.checkoutSelectionService = checkoutSelectionService;
        this.appAddressService = appAddressService;
        this.appAfterSaleService = appAfterSaleService;
        this.shippingUploadRecovery = shippingUploadRecovery;
        this.orderStatusLogService = orderStatusLogService;
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
        CheckoutRequest checkoutRequest = CheckoutRequest.from(request);
        checkoutSelectionService.validate(checkoutRequest);
        validateSubmitRequest(request, checkoutRequest);

        Optional<ExistingOrder> existing = findExistingOrder(userId, request.idempotencyKey(), false);
        if (existing.isPresent()) {
            return replayExisting(existing.get(), checkoutRequest);
        }

        LocalDateTime now = LocalDateTime.now();
        String orderNo = nextOrderNo(now);
        Long orderId;
        try {
            orderId = insertOrderOwnership(
                    userId,
                    orderNo,
                    checkoutRequest.source(),
                    request.idempotencyKey(),
                    CheckoutRequestDigest.digest(checkoutRequest),
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
        List<Long> orderItemIds = insertOrderItems(orderId, selection.previewItems(), now);
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

        return new OrderSubmitResponse(
                orderId,
                orderNo,
                OrderStatus.CREATED.name(),
                payableAmountCent,
                coupon.discountCent(),
                now
        );
    }

    public PageResult<OrderSummaryResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status
    ) {
        return list(principal, current, size, status, OrderStatusGroup.ALL);
    }

    public PageResult<OrderSummaryResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status,
            OrderStatusGroup statusGroup
    ) {
        Long userId = requireAppUser(principal);
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        long offset = (pageCurrent - 1) * pageSize;
        String normalizedStatus = normalizeStatus(status);
        OrderStatusGroup normalizedStatusGroup = statusGroup == null ? OrderStatusGroup.ALL : statusGroup;
        if (normalizedStatus != null && normalizedStatusGroup != OrderStatusGroup.ALL) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        boolean allStatuses = normalizedStatusGroup == OrderStatusGroup.ALL;
        List<String> groupedStatuses = allStatuses
                ? List.of("__ALL_STATUS_GROUP__")
                : statusesForGroup(normalizedStatusGroup);

        Long total = jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where user_id = :userId
                          and (:status is null or status = :status)
                          and (:allStatuses = true or status in (:groupedStatuses))
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .param("allStatuses", allStatuses)
                .param("groupedStatuses", groupedStatuses)
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
                        where o.user_id = :userId
                          and (:status is null or o.status = :status)
                          and (:allStatuses = true or o.status in (:groupedStatuses))
                        order by o.created_at desc, o.id desc
                        limit :limit offset :offset
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .param("allStatuses", allStatuses)
                .param("groupedStatuses", groupedStatuses)
                .param("limit", pageSize)
                .param("offset", offset)
                .query(this::mapOrderSummary)
                .list();

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
        AfterSaleResponse latestAfterSale = appAfterSaleService.latestForOrder(principal, orderId);

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
                findShipment(orderId),
                latestAfterSale,
                items
        );
    }

    @Transactional
    public OrderReceiptResponse confirmReceipt(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        ReceiptOrder order = jdbcClient.sql("""
                        select id as order_id,
                               status,
                               completed_at
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
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

        if (OrderStatus.COMPLETED.name().equals(order.status())) {
            return new OrderReceiptResponse(order.orderId(), order.status(), order.completedAt());
        }
        if (!OrderStatus.SHIPPED.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        LocalDateTime completedAt = LocalDateTime.now().withNano(0);
        jdbcClient.sql("""
                        update shop_order
                        set status = :completedStatus,
                            completed_at = :completedAt,
                            updated_at = :completedAt
                        where id = :orderId
                        """)
                .param("completedStatus", OrderStatus.COMPLETED.name())
                .param("completedAt", completedAt)
                .param("orderId", order.orderId())
                .update();
        orderStatusLogService.record(
                order.orderId(), OrderStatus.SHIPPED.name(), OrderStatus.COMPLETED.name(),
                "ORDER_COMPLETED", OPERATOR_TYPE_APP, userId, "用户确认收货", completedAt
        );
        return new OrderReceiptResponse(order.orderId(), OrderStatus.COMPLETED.name(), completedAt);
    }

    private AppliedCoupon resolveCoupon(Long userId, Long userCouponId, CheckoutContext context, boolean forUpdate) {
        LocalDateTime now = LocalDateTime.now();
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
        return jdbcClient.sql(sql)
                .param("userId", userId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapExistingOrder)
                .optional();
    }

    private OrderSubmitResponse replayExisting(ExistingOrder existing, CheckoutRequest request) {
        String freightAwareDigest = CheckoutRequestDigest.digest(request, existing.freightCent());
        String legacyDigest = CheckoutRequestDigest.digest(request);
        if (!StringUtils.hasText(existing.checkoutRequestDigest())
                || existing.checkoutRequestDigest().equals(freightAwareDigest)
                || existing.checkoutRequestDigest().equals(legacyDigest)) {
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
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into shop_order (
                            order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                            created_at, updated_at
                        )
                        values (
                            :orderNo, :userId, :status, :source, :idempotencyKey, :checkoutRequestDigest,
                            :createdAt, :updatedAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("orderNo", orderNo)
                        .addValue("userId", userId)
                        .addValue("status", OrderStatus.CREATED.name())
                        .addValue("source", source.name())
                        .addValue("idempotencyKey", idempotencyKey)
                        .addValue("checkoutRequestDigest", checkoutRequestDigest)
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
                .param("updatedAt", now)
                .param("orderId", orderId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private List<Long> insertOrderItems(Long orderId, List<OrderPreviewItemResponse> items, LocalDateTime now) {
        List<Long> orderItemIds = new ArrayList<>(items.size());
        for (OrderPreviewItemResponse item : items) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            namedParameterJdbcTemplate.update("""
                            insert into order_item (
                                order_id, sku_id, spu_id, product_title, product_subtitle, main_image,
                                main_image_file_id, sku_image, sku_image_file_id, display_image, display_image_file_id,
                                sku_code, spec_text, original_price_cent,
                                unit_price_cent, quantity, line_original_amount_cent, line_amount_cent, created_at
                            )
                            values (
                                :orderId, :skuId, :spuId, :productTitle, :productSubtitle, :mainImage,
                                :mainImageFileId, :skuImage, :skuImageFileId, :displayImage, :displayImageFileId, :skuCode, :specText, :originalPriceCent,
                                :unitPriceCent, :quantity, :lineOriginalAmountCent, :lineAmountCent, :createdAt
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
                            .addValue("quantity", item.quantity())
                            .addValue("lineOriginalAmountCent", item.lineOriginalAmountCent())
                            .addValue("lineAmountCent", item.lineAmountCent())
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

    private AppOrderShipmentResponse findShipment(Long orderId) {
        return jdbcClient.sql("""
                        select id as shipment_id,
                               order_id,
                               logistics_type,
                               delivery_mode,
                               item_desc,
                               express_company_code,
                               express_company_name,
                               tracking_no,
                               status as local_shipment_status,
                               wechat_provider_mode,
                               wechat_upload_status,
                               shipped_at,
                               upload_time,
                               wechat_uploaded_at
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(this::mapShipment)
                .optional()
                .orElse(null);
    }

    private AppOrderShipmentResponse mapShipment(ResultSet rs, int rowNum) throws SQLException {
        WechatProviderMode providerMode = providerMode(rs.getString("wechat_provider_mode"));
        WechatShippingUploadStatus uploadStatus = uploadStatus(rs.getString("wechat_upload_status"));
        return new AppOrderShipmentResponse(
                rs.getLong("shipment_id"),
                rs.getLong("order_id"),
                LogisticsType.fromValue(rs.getInt("logistics_type")),
                DeliveryMode.fromValue(rs.getInt("delivery_mode")),
                rs.getString("item_desc"),
                rs.getString("express_company_code"),
                rs.getString("express_company_name"),
                rs.getString("tracking_no"),
                rs.getString("local_shipment_status"),
                providerMode,
                uploadStatus,
                uploadMessage(providerMode, uploadStatus),
                rs.getObject("shipped_at", LocalDateTime.class),
                rs.getString("upload_time"),
                rs.getObject("wechat_uploaded_at", LocalDateTime.class)
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

    private String uploadMessage(WechatProviderMode mode, WechatShippingUploadStatus status) {
        if (mode == WechatProviderMode.REAL && status == WechatShippingUploadStatus.UPLOADED) {
            return "WeChat has accepted the shipping information";
        }
        return switch (status) {
            case SKIPPED -> "Shipping information is pending platform upload";
            case UPLOADING -> "Shipping information is being uploaded";
            case FAILED -> "Shipping information has not been uploaded yet";
            case UNAVAILABLE -> "Platform shipping service is currently unavailable";
            case UNKNOWN -> "Platform upload status is being confirmed";
            case UPLOADED -> "Shipping information was saved locally";
        };
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

    private List<String> statusesForGroup(OrderStatusGroup statusGroup) {
        return switch (statusGroup) {
            case UNPAID -> List.of(OrderStatus.CREATED.name(), OrderStatus.PAYING.name());
            case TO_SHIP -> List.of(OrderStatus.PAID.name());
            case TO_RECEIVE -> List.of(OrderStatus.SHIPPED.name());
            case COMPLETED -> List.of(OrderStatus.COMPLETED.name());
            case ALL -> List.of("__ALL_STATUS_GROUP__");
        };
    }

    private String nextOrderNo(LocalDateTime now) {
        long sequence = ORDER_NO_SEQUENCE.incrementAndGet() % 1_000_000L;
        return "ORD" + now.format(ORDER_NO_TIME_FORMATTER) + String.format("%06d", sequence);
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
            LocalDateTime paidAt
    ) {
    }

    private record ReceiptOrder(
            Long orderId,
            String status,
            LocalDateTime completedAt
    ) {
    }
}
