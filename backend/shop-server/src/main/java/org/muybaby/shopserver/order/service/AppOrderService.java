package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.OrderDetailResponse;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;
import org.muybaby.shopserver.order.dto.OrderPreviewResponse;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.promotion.CheckoutContext;
import org.muybaby.shopserver.promotion.CheckoutItem;
import org.muybaby.shopserver.promotion.CouponCandidate;
import org.muybaby.shopserver.promotion.CouponDiscountCalculator;
import org.muybaby.shopserver.promotion.DiscountResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AppOrderService {

    private static final String CATEGORY_ENABLED = "ENABLED";
    private static final String ORDER_SOURCE_CART = "CART";
    private static final String OPERATOR_TYPE_APP = "APP";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong ORDER_NO_SEQUENCE = new AtomicLong();

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final CouponDiscountCalculator couponDiscountCalculator = new CouponDiscountCalculator();

    public AppOrderService(JdbcClient jdbcClient, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public OrderPreviewResponse preview(AuthenticatedPrincipal principal, AppOrderPreviewRequest request) {
        Long userId = requireAppUser(principal);
        CheckoutSelection selection = loadCheckoutSelection(userId, request == null ? null : request.cartItemIds(), false);
        AppliedCoupon coupon = resolveCoupon(userId, request == null ? null : request.userCouponId(), selection.context(), false);
        return toPreviewResponse(selection, coupon);
    }

    @Transactional
    public OrderSubmitResponse submit(AuthenticatedPrincipal principal, AppOrderSubmitRequest request) {
        Long userId = requireAppUser(principal);
        Optional<OrderSubmitResponse> existing = findExistingOrder(userId, request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now();
        String orderNo = nextOrderNo(now);
        Long orderId;
        try {
            orderId = insertOrderOwnership(userId, orderNo, request.idempotencyKey(), now);
        } catch (DuplicateKeyException ex) {
            return findExistingOrder(userId, request.idempotencyKey()).orElseThrow(() -> ex);
        }

        CheckoutSelection selection = loadCheckoutSelection(userId, request.cartItemIds(), true);
        AppliedCoupon coupon = resolveCoupon(userId, request.userCouponId(), selection.context(), true);
        long payableAmountCent = Math.max(selection.productAmountCent() - coupon.discountCent(), 0L);
        updateOrderAmounts(orderId, selection, coupon, payableAmountCent, now);
        List<Long> orderItemIds = insertOrderItems(orderId, selection.items(), now);
        applyStockLocks(userId, orderId, orderItemIds, selection.items(), now);
        if (coupon.userCouponId() != null) {
            lockCoupon(userId, coupon.userCouponId(), orderId, now);
        }
        deleteCartItems(userId, selection.cartItemIds());

        return new OrderSubmitResponse(
                orderId,
                orderNo,
                OrderStatus.CREATED.name(),
                payableAmountCent,
                coupon.discountCent(),
                now
        );
    }

    public PageResult<OrderSummaryResponse> list(AuthenticatedPrincipal principal, Long current, Long size, String status) {
        Long userId = requireAppUser(principal);
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        long offset = (pageCurrent - 1) * pageSize;
        String normalizedStatus = normalizeStatus(status);

        Long total = jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where user_id = :userId
                          and (:status is null or status = :status)
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
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
                        order by o.created_at desc, o.id desc
                        limit :limit offset :offset
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .param("limit", pageSize)
                .param("offset", offset)
                .query(this::mapOrderSummary)
                .list();

        return PageResult.of(records, total == null ? 0L : total, pageCurrent, pageSize);
    }

    public OrderDetailResponse detail(AuthenticatedPrincipal principal, Long orderId) {
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
                               close_reason,
                               closed_at,
                               created_at
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
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

    private CheckoutSelection loadCheckoutSelection(Long userId, List<Long> cartItemIds, boolean forUpdate) {
        List<Long> normalizedCartItemIds = normalizeCartItemIds(cartItemIds);
        List<CartSelectionRow> selectedCartRows = findOwnedCartRows(userId, normalizedCartItemIds, forUpdate);
        if (normalizedCartItemIds != null && selectedCartRows.size() != normalizedCartItemIds.size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        if (selectedCartRows.isEmpty()) {
            throw new BusinessException(normalizedCartItemIds == null ? ErrorCode.VALIDATION_FAILED : ErrorCode.CART_ITEM_NOT_FOUND);
        }
        if (forUpdate) {
            lockSkuRows(selectedCartRows.stream().map(CartSelectionRow::skuId).toList());
        }

        List<Long> selectedIds = selectedCartRows.stream().map(CartSelectionRow::cartItemId).toList();
        List<CheckoutRow> checkoutRows = findCheckoutRowsByCartIds(userId, selectedIds);
        if (checkoutRows.size() != selectedCartRows.size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }

        List<OrderPreviewItemResponse> previewItems = new ArrayList<>(checkoutRows.size());
        List<CheckoutItem> checkoutItems = new ArrayList<>(checkoutRows.size());
        long productOriginalAmountCent = 0L;
        long productAmountCent = 0L;
        for (CheckoutRow row : checkoutRows) {
            validateCheckoutRow(row);
            long lineOriginalAmountCent = row.originalPriceCent() * row.quantity();
            long lineAmountCent = row.unitPriceCent() * row.quantity();
            productOriginalAmountCent += lineOriginalAmountCent;
            productAmountCent += lineAmountCent;
            previewItems.add(new OrderPreviewItemResponse(
                    row.cartItemId(),
                    row.skuId(),
                    row.spuId(),
                    row.productTitle(),
                    row.productSubtitle(),
                    row.mainImage(),
                    row.skuImage(),
                    row.displayImage(),
                    row.skuCode(),
                    row.specText(),
                    row.originalPriceCent(),
                    row.unitPriceCent(),
                    row.quantity(),
                    lineOriginalAmountCent,
                    lineAmountCent
            ));
            checkoutItems.add(new CheckoutItem(
                    row.skuId(),
                    row.spuId(),
                    lineAmountCent,
                    row.quantity()
            ));
        }

        return new CheckoutSelection(
                previewItems,
                checkoutItems,
                selectedIds,
                productOriginalAmountCent,
                productAmountCent,
                new CheckoutContext(userId, checkoutItems)
        );
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

    private Optional<OrderSubmitResponse> findExistingOrder(Long userId, String idempotencyKey) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               status,
                               payable_amount_cent,
                               coupon_discount_cent,
                               created_at
                        from shop_order
                        where user_id = :userId
                          and idempotency_key = :idempotencyKey
                        """)
                .param("userId", userId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapOrderSubmit)
                .optional();
    }

    private Long insertOrderOwnership(
            Long userId,
            String orderNo,
            String idempotencyKey,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into shop_order (
                            order_no, user_id, status, source, idempotency_key,
                            product_original_amount_cent, product_amount_cent, user_coupon_id, coupon_name,
                            coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                            created_at, updated_at
                        )
                        values (
                            :orderNo, :userId, :status, :source, :idempotencyKey,
                            :productOriginalAmountCent, :productAmountCent, :userCouponId, :couponName,
                            :couponDiscountCent, :freightCent, :payableAmountCent, :paidAmountCent,
                            :createdAt, :updatedAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("orderNo", orderNo)
                        .addValue("userId", userId)
                        .addValue("status", OrderStatus.CREATED.name())
                        .addValue("source", ORDER_SOURCE_CART)
                        .addValue("idempotencyKey", idempotencyKey)
                        .addValue("productOriginalAmountCent", 0L)
                        .addValue("productAmountCent", 0L)
                        .addValue("userCouponId", null)
                        .addValue("couponName", "")
                        .addValue("couponDiscountCent", 0L)
                        .addValue("freightCent", 0L)
                        .addValue("payableAmountCent", 0L)
                        .addValue("paidAmountCent", 0L)
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
            long payableAmountCent,
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
                            updated_at = :updatedAt
                        where id = :orderId
                        """)
                .param("productOriginalAmountCent", selection.productOriginalAmountCent())
                .param("productAmountCent", selection.productAmountCent())
                .param("userCouponId", coupon.userCouponId())
                .param("couponName", defaultString(coupon.couponName()))
                .param("couponDiscountCent", coupon.discountCent())
                .param("freightCent", 0L)
                .param("payableAmountCent", payableAmountCent)
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
                                sku_image, display_image, sku_code, spec_text, original_price_cent,
                                unit_price_cent, quantity, line_original_amount_cent, line_amount_cent, created_at
                            )
                            values (
                                :orderId, :skuId, :spuId, :productTitle, :productSubtitle, :mainImage,
                                :skuImage, :displayImage, :skuCode, :specText, :originalPriceCent,
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
                            .addValue("skuImage", defaultString(item.skuImage()))
                            .addValue("displayImage", defaultString(item.displayImage()))
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
            orderItemIds.add(requireGeneratedId(keyHolder));
        }
        return orderItemIds;
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

    private List<CartSelectionRow> findOwnedCartRows(Long userId, List<Long> cartItemIds, boolean forUpdate) {
        StringBuilder sql = new StringBuilder("""
                select id as cart_item_id, sku_id, quantity
                from cart_item
                where user_id = :userId
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId);
        if (cartItemIds != null) {
            sql.append(" and id in (:cartItemIds)");
            parameters.addValue("cartItemIds", cartItemIds);
        }
        sql.append(" order by id asc");
        if (forUpdate) {
            sql.append(" for update");
        }
        return namedParameterJdbcTemplate.query(sql.toString(), parameters, mapCartSelectionRow());
    }

    private void lockSkuRows(List<Long> skuIds) {
        List<Long> normalizedSkuIds = distinctIds(skuIds);
        if (normalizedSkuIds.isEmpty()) {
            return;
        }
        namedParameterJdbcTemplate.query("""
                        select id
                        from product_sku
                        where id in (:skuIds)
                        for update
                        """,
                new MapSqlParameterSource().addValue("skuIds", normalizedSkuIds),
                (rs, rowNum) -> rs.getLong("id"));
    }

    private List<CheckoutRow> findCheckoutRowsByCartIds(Long userId, List<Long> cartItemIds) {
        return namedParameterJdbcTemplate.query("""
                        select c.id as cart_item_id,
                               c.sku_id,
                               c.quantity,
                               k.spu_id,
                               s.title as product_title,
                               s.subtitle as product_subtitle,
                               s.main_image,
                               k.image as sku_image,
                               case
                                   when k.image is null or k.image = '' then s.main_image
                                   else k.image
                               end as display_image,
                               k.sku_code,
                               k.spec_text,
                               k.original_price_cent,
                               k.price_cent as unit_price_cent,
                               k.stock_available,
                               k.status as sku_status,
                               s.status as spu_status,
                               pc.status as category_status
                        from cart_item c
                        join product_sku k on k.id = c.sku_id
                        join product_spu s on s.id = k.spu_id
                        join product_category pc on pc.id = s.category_id
                        where c.user_id = :userId
                          and c.id in (:cartItemIds)
                        order by c.id asc
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("cartItemIds", cartItemIds),
                this::mapCheckoutRow);
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
        long payableAmountCent = Math.max(selection.productAmountCent() - coupon.discountCent(), 0L);
        return new OrderPreviewResponse(
                selection.items(),
                selection.productOriginalAmountCent(),
                selection.productAmountCent(),
                coupon.userCouponId(),
                defaultString(coupon.couponName()),
                coupon.discountCent(),
                0L,
                payableAmountCent
        );
    }

    private void validateCheckoutRow(CheckoutRow row) {
        if (!SkuStatus.ENABLED.name().equals(row.skuStatus())) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        if (!ProductStatus.ON_SALE.name().equals(row.spuStatus()) || !CATEGORY_ENABLED.equals(row.categoryStatus())) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        if (row.stockAvailable() < row.quantity()) {
            throw new BusinessException(ErrorCode.STOCK_SHORTAGE);
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

    private OrderSubmitResponse mapOrderSubmit(ResultSet rs, int rowNum) throws SQLException {
        return new OrderSubmitResponse(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("coupon_discount_cent"),
                rs.getObject("created_at", LocalDateTime.class)
        );
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

    private CheckoutRow mapCheckoutRow(ResultSet rs, int rowNum) throws SQLException {
        return new CheckoutRow(
                rs.getLong("cart_item_id"),
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
                rs.getInt("stock_available"),
                rs.getString("sku_status"),
                rs.getString("spu_status"),
                rs.getString("category_status")
        );
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

    private RowMapper<CartSelectionRow> mapCartSelectionRow() {
        return (rs, rowNum) -> new CartSelectionRow(
                rs.getLong("cart_item_id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private long normalizeCurrent(Long current) {
        return current == null || current < 1 ? 1L : current;
    }

    private long normalizeSize(Long size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim() : null;
    }

    private List<Long> normalizeCartItemIds(List<Long> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return null;
        }
        return distinctIds(cartItemIds);
    }

    private List<Long> distinctIds(List<Long> ids) {
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
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

    private record CartSelectionRow(Long cartItemId, Long skuId, Integer quantity) {
    }

    private record CheckoutRow(
            Long cartItemId,
            Long skuId,
            Long spuId,
            String productTitle,
            String productSubtitle,
            String mainImage,
            String skuImage,
            String displayImage,
            String skuCode,
            String specText,
            Long originalPriceCent,
            Long unitPriceCent,
            Integer quantity,
            Integer stockAvailable,
            String skuStatus,
            String spuStatus,
            String categoryStatus
    ) {
    }

    private record CheckoutSelection(
            List<OrderPreviewItemResponse> items,
            List<CheckoutItem> checkoutItems,
            List<Long> cartItemIds,
            long productOriginalAmountCent,
            long productAmountCent,
            CheckoutContext context
    ) {
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
            String closeReason,
            LocalDateTime closedAt,
            LocalDateTime createdAt
    ) {
    }
}
