package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.AfterSaleType;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEligibilityItemResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEligibilityResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleItemRequest;
import org.muybaby.shopserver.aftersale.dto.AfterSaleQuoteItemResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleQuoteResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleQuoteRequest;
import org.muybaby.shopserver.aftersale.dto.AppReturnShipmentRequest;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageAssetScope;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.muybaby.shopserver.user.service.AppUserService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AppAfterSaleV2Service {

    private static final Set<String> ELIGIBLE_ORDER_STATUSES = Set.of(
            OrderStatus.PAID.name(), OrderStatus.SHIPPED.name(), OrderStatus.COMPLETED.name());
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            AfterSaleStatus.REQUESTED.name(), AfterSaleStatus.APPROVED.name(),
            AfterSaleStatus.WAITING_RETURN.name(), AfterSaleStatus.RETURNING.name(),
            AfterSaleStatus.WAITING_INSPECTION.name(), AfterSaleStatus.REFUNDING.name(),
            AfterSaleStatus.REFUND_FAILED.name());

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final AppUserService appUserService;
    private final StorageUsageService storageUsageService;
    private final OrderStatusLogService orderStatusLogService;
    private final AfterSaleStatusLogService statusLogService;
    private final AfterSaleV2ReadService readService;
    private final AfterSaleReturnExpiryService returnExpiryService;

    public AppAfterSaleV2Service(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            AppUserService appUserService,
            StorageUsageService storageUsageService,
            OrderStatusLogService orderStatusLogService,
            AfterSaleStatusLogService statusLogService,
            AfterSaleV2ReadService readService,
            AfterSaleReturnExpiryService returnExpiryService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.appUserService = appUserService;
        this.storageUsageService = storageUsageService;
        this.orderStatusLogService = orderStatusLogService;
        this.statusLogService = statusLogService;
        this.readService = readService;
        this.returnExpiryService = returnExpiryService;
    }

    public AfterSaleEligibilityResponse eligibility(
            AuthenticatedPrincipal principal,
            long orderId
    ) {
        long userId = requireAppUser(principal);
        returnExpiryService.expireDueForOrder(orderId);
        OrderSnapshot order = requireOwnedOrder(orderId, userId, false);
        List<ItemSnapshot> items = itemSnapshots(order);
        Long activeId = activeAfterSaleId(orderId);
        List<AfterSaleEligibilityItemResponse> itemResponses = items.stream()
                .map(item -> new AfterSaleEligibilityItemResponse(
                        item.orderItemId(), item.skuId(), item.title(), item.specText(), item.image(),
                        item.quantity(), item.refundedQuantity(),
                        Math.max(0, item.quantity() - item.refundedQuantity()), item.paidAmountBasisCent()))
                .toList();
        return new AfterSaleEligibilityResponse(
                order.orderId(), order.orderNo(), order.status(), activeId,
                order.paidAmountCent(), order.refundedAmountCent(),
                Math.max(0, order.paidAmountCent() - order.refundedAmountCent()),
                ELIGIBLE_ORDER_STATUSES.contains(order.status())
                        ? List.of(AfterSaleType.REFUND_ONLY.name(), AfterSaleType.RETURN_REFUND.name())
                        : List.of(),
                itemResponses);
    }

    public AfterSaleQuoteResponse quote(
            AuthenticatedPrincipal principal,
            long orderId,
            AppAfterSaleQuoteRequest request
    ) {
        long userId = requireAppUser(principal);
        returnExpiryService.expireDueForOrder(orderId);
        OrderSnapshot order = requireOwnedOrder(orderId, userId, false);
        AfterSaleType type = parseType(request == null ? null : request.afterSaleType());
        return quote(order, type, request == null ? null : request.items());
    }

    @Transactional
    public AfterSaleResponse apply(
            AuthenticatedPrincipal principal,
            long orderId,
            AppAfterSaleApplyRequest request
    ) {
        long userId = requireAppUser(principal);
        returnExpiryService.expireDueForOrder(orderId);
        appUserService.requireEnabledUserForUpdate(userId);
        AfterSaleType type = parseType(request == null ? null : request.afterSaleType());
        String reason = requireText(request == null ? null : request.reason(), 128);
        String description = text(request == null ? null : request.description(), 500);
        List<Long> evidenceIds = evidenceIds(request == null ? null : request.evidenceFileIds());
        String requestKey = optionalKey(request == null ? null : request.requestKey());

        OrderSnapshot order = requireOwnedOrder(orderId, userId, true);
        lockAfterSales(orderId);
        List<AfterSaleItemRequest> selectedItems = request == null ? null : request.items();
        if (selectedItems == null || selectedItems.isEmpty()) {
            selectedItems = itemSnapshots(order).stream()
                    .filter(item -> item.refundedQuantity() < item.quantity())
                    .map(item -> new AfterSaleItemRequest(
                            item.orderItemId(), item.quantity() - item.refundedQuantity()))
                    .toList();
        }
        String requestDigest = requestDigest(type, selectedItems, reason, description, evidenceIds);
        if (requestKey != null) {
            ExistingRequest existing = existingRequest(userId, orderId, requestKey);
            if (existing != null) {
                if (!requestDigest.equals(existing.requestDigest())) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
                }
                return requireDecorated(existing.afterSaleId(), userId);
            }
        }
        if (!ELIGIBLE_ORDER_STATUSES.contains(order.status()) || activeAfterSaleId(orderId) != null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        AfterSaleQuoteResponse quote = quote(order, type, selectedItems);
        if (quote.requestedAmountCent() <= 0
                || request != null && request.requestedAmountCent() != null
                && !request.requestedAmountCent().equals(quote.requestedAmountCent())
                || request != null && StringUtils.hasText(request.quoteDigest())
                && !request.quoteDigest().trim().equals(quote.quoteDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        validateEvidence(userId, orderId, evidenceIds);

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        String afterSaleNo = AppAfterSaleService.nextAfterSaleNo(now);
        long afterSaleId = insertAfterSale(
                order, userId, type, afterSaleNo, requestKey, requestDigest,
                reason, description, quote.requestedAmountCent(), now);
        insertItems(afterSaleId, order, quote, now);
        insertEvidence(afterSaleId, evidenceIds);
        protectEvidence(afterSaleId, afterSaleNo, order.orderNo(), evidenceIds);
        claimEvidence(evidenceIds);
        statusLogService.record(
                afterSaleId, "", AfterSaleStatus.REQUESTED.name(),
                "AFTER_SALE_REQUESTED", "APP", userId, "用户申请售后", now);
        orderStatusLogService.record(
                orderId, afterSaleId, order.status(), order.status(),
                "AFTER_SALE_REQUESTED", "APP", userId, "用户申请售后", now);
        return requireDecorated(afterSaleId, userId);
    }

    @Transactional
    public AfterSaleResponse cancel(
            AuthenticatedPrincipal principal,
            long afterSaleId
    ) {
        long userId = requireAppUser(principal);
        Route route = requireOwnedRoute(afterSaleId, userId);
        returnExpiryService.expireDueForOrder(route.orderId());
        OrderSnapshot order = requireOwnedOrder(route.orderId(), userId, true);
        AfterSaleState state = requireAfterSaleForUpdate(afterSaleId, userId);
        if (!Set.of(AfterSaleStatus.REQUESTED.name(), AfterSaleStatus.WAITING_RETURN.name())
                .contains(state.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status, cancelled_at = :now,
                            version = version + 1, updated_at = :now
                        where id = :id and status = :expected
                        """)
                .param("status", AfterSaleStatus.CANCELLED.name())
                .param("now", now)
                .param("id", afterSaleId)
                .param("expected", state.status())
                .update();
        requireOne(updated);
        statusLogService.record(
                afterSaleId, state.status(), AfterSaleStatus.CANCELLED.name(),
                "AFTER_SALE_CANCELLED", "APP", userId, "用户取消售后", now);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                "AFTER_SALE_CANCELLED", "APP", userId, "用户取消售后", now);
        return requireDecorated(afterSaleId, userId);
    }

    @Transactional
    public AfterSaleResponse submitReturnShipment(
            AuthenticatedPrincipal principal,
            long afterSaleId,
            AppReturnShipmentRequest request
    ) {
        long userId = requireAppUser(principal);
        Route route = requireOwnedRoute(afterSaleId, userId);
        returnExpiryService.expireDueForOrder(route.orderId());
        OrderSnapshot order = requireOwnedOrder(route.orderId(), userId, true);
        AfterSaleState state = requireAfterSaleForUpdate(afterSaleId, userId);
        if (!AfterSaleType.RETURN_REFUND.name().equals(state.type())
                || !Set.of(AfterSaleStatus.WAITING_RETURN.name(), AfterSaleStatus.RETURNING.name())
                .contains(state.status())
                || state.returnDeadlineAt() != null
                && state.returnDeadlineAt().isBefore(LocalDateTime.now(java.time.ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        String companyCode = text(request == null ? null : request.deliveryCompanyCode(), 128);
        String companyName = requireText(request == null ? null : request.deliveryCompanyName(), 128);
        String trackingNo = requireText(request == null ? null : request.trackingNo(), 80);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int returnRows = jdbcClient.sql("""
                        update after_sale_return
                        set delivery_company_code = :companyCode,
                            delivery_company_name = :companyName,
                            tracking_no = :trackingNo,
                            user_shipped_at = coalesce(user_shipped_at, :now),
                            version = version + 1,
                            updated_at = :now
                        where after_sale_id = :afterSaleId
                        """)
                .param("companyCode", companyCode)
                .param("companyName", companyName)
                .param("trackingNo", trackingNo)
                .param("now", now)
                .param("afterSaleId", afterSaleId)
                .update();
        requireOne(returnRows);
        if (AfterSaleStatus.WAITING_RETURN.name().equals(state.status())) {
            requireOne(jdbcClient.sql("""
                            update after_sale_request
                            set status = :status, version = version + 1, updated_at = :now
                            where id = :id and status = :expected
                            """)
                    .param("status", AfterSaleStatus.RETURNING.name())
                    .param("now", now)
                    .param("id", afterSaleId)
                    .param("expected", AfterSaleStatus.WAITING_RETURN.name())
                    .update());
            statusLogService.record(
                    afterSaleId, state.status(), AfterSaleStatus.RETURNING.name(),
                    "RETURN_SHIPMENT_SUBMITTED", "APP", userId, "用户提交退货物流", now);
        } else {
            statusLogService.record(
                    afterSaleId, state.status(), state.status(),
                    "RETURN_SHIPMENT_UPDATED", "APP", userId, "用户更新退货物流", now);
        }
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                "RETURN_SHIPMENT_SUBMITTED", "APP", userId, "用户提交退货物流", now);
        return requireDecorated(afterSaleId, userId);
    }

    private AfterSaleQuoteResponse quote(
            OrderSnapshot order,
            AfterSaleType type,
            List<AfterSaleItemRequest> requestedItems
    ) {
        if (!ELIGIBLE_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        Map<Long, ItemSnapshot> byId = new LinkedHashMap<>();
        for (ItemSnapshot item : itemSnapshots(order)) {
            byId.put(item.orderItemId(), item);
        }
        List<AfterSaleItemRequest> normalized = normalizeItems(requestedItems);
        List<AfterSaleQuoteItemResponse> result = new ArrayList<>(normalized.size());
        long total = 0;
        for (AfterSaleItemRequest request : normalized) {
            ItemSnapshot item = byId.get(request.orderItemId());
            if (item == null || request.quantity() == null || request.quantity() <= 0
                    || item.refundedQuantity() + request.quantity() > item.quantity()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            long amount = AfterSaleAmountAllocator.tranche(
                    item.paidAmountBasisCent(), item.quantity(),
                    item.refundedQuantity(), request.quantity());
            total = Math.addExact(total, amount);
            result.add(new AfterSaleQuoteItemResponse(item.orderItemId(), request.quantity(), amount));
        }
        if (total <= 0 || total > order.paidAmountCent() - order.refundedAmountCent()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String digest = sha256(order.orderId() + "|" + type.name() + "|"
                + order.refundedAmountCent() + "|" + result.stream()
                .map(item -> item.orderItemId() + ":" + item.quantity() + ":" + item.requestedAmountCent())
                .reduce((left, right) -> left + "," + right).orElse(""));
        return new AfterSaleQuoteResponse(order.orderId(), type.name(), total, digest, List.copyOf(result));
    }

    private List<ItemSnapshot> itemSnapshots(OrderSnapshot order) {
        List<RawItem> raw = jdbcClient.sql("""
                        select id, sku_id, product_title, spec_text,
                               case when display_image <> '' then display_image
                                    when sku_image <> '' then sku_image
                                    else main_image end as image,
                               quantity, refunded_quantity, line_amount_cent,
                               paid_amount_allocated_cent
                        from order_item
                        where order_id = :orderId
                        order by id
                        """)
                .param("orderId", order.orderId())
                .query(this::mapRawItem)
                .list();
        if (raw.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        boolean storedAllocationValid = raw.stream().allMatch(item -> item.paidAmountAllocatedCent() != null)
                && raw.stream().mapToLong(item -> item.paidAmountAllocatedCent()).sum() == order.paidAmountCent();
        List<Long> bases = storedAllocationValid
                ? raw.stream().map(RawItem::paidAmountAllocatedCent).toList()
                : proportional(order.paidAmountCent(), raw.stream().map(RawItem::lineAmountCent).toList());
        List<ItemSnapshot> result = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            RawItem item = raw.get(index);
            result.add(new ItemSnapshot(
                    item.orderItemId(), item.skuId(), item.title(), item.specText(), item.image(),
                    item.quantity(), item.refundedQuantity(), item.lineAmountCent(), bases.get(index)));
        }
        return List.copyOf(result);
    }

    private List<Long> proportional(long amount, List<Long> weights) {
        long sum = weights.stream().mapToLong(Long::longValue).sum();
        List<Long> effective = sum == 0 ? weights.stream().map(ignored -> 1L).toList() : weights;
        long divisorValue = sum == 0 ? effective.size() : sum;
        BigInteger divisor = BigInteger.valueOf(divisorValue);
        List<Share> shares = new ArrayList<>(effective.size());
        long allocated = 0;
        for (int index = 0; index < effective.size(); index++) {
            BigInteger[] values = BigInteger.valueOf(amount)
                    .multiply(BigInteger.valueOf(effective.get(index)))
                    .divideAndRemainder(divisor);
            long floor = values[0].longValueExact();
            allocated = Math.addExact(allocated, floor);
            shares.add(new Share(index, floor, values[1]));
        }
        long remainder = amount - allocated;
        List<Share> order = shares.stream()
                .sorted(Comparator.comparing(Share::remainder).reversed().thenComparingInt(Share::index))
                .toList();
        for (int index = 0; index < remainder; index++) {
            Share share = order.get(index);
            shares.set(share.index(), new Share(share.index(), share.amount() + 1, share.remainder()));
        }
        return shares.stream().map(Share::amount).toList();
    }

    private long insertAfterSale(
            OrderSnapshot order,
            long userId,
            AfterSaleType type,
            String afterSaleNo,
            String requestKey,
            String requestDigest,
            String reason,
            String description,
            long amount,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into after_sale_request (
                            after_sale_no, order_id, user_id, after_sale_type, status,
                            reason, description, requested_amount_cent,
                            flow_version, request_key, request_digest, source_order_status,
                            created_at, updated_at
                        ) values (
                            :afterSaleNo, :orderId, :userId, :type, :status,
                            :reason, :description, :amount,
                            2, :requestKey, :requestDigest, :sourceOrderStatus,
                            :now, :now
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("afterSaleNo", afterSaleNo)
                        .addValue("orderId", order.orderId())
                        .addValue("userId", userId)
                        .addValue("type", type.name())
                        .addValue("status", AfterSaleStatus.REQUESTED.name())
                        .addValue("reason", reason)
                        .addValue("description", description)
                        .addValue("amount", amount)
                        .addValue("requestKey", requestKey)
                        .addValue("requestDigest", requestDigest)
                        .addValue("sourceOrderStatus", order.status())
                        .addValue("now", now),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return key.longValue();
    }

    private void insertItems(
            long afterSaleId,
            OrderSnapshot order,
            AfterSaleQuoteResponse quote,
            LocalDateTime now
    ) {
        Map<Long, ItemSnapshot> snapshots = new LinkedHashMap<>();
        for (ItemSnapshot item : itemSnapshots(order)) {
            snapshots.put(item.orderItemId(), item);
        }
        for (AfterSaleQuoteItemResponse item : quote.items()) {
            ItemSnapshot snapshot = snapshots.get(item.orderItemId());
            int updated = jdbcClient.sql("""
                            insert into after_sale_item (
                                after_sale_id, order_item_id, sku_id, order_quantity_snapshot,
                                paid_amount_basis_cent, refunded_quantity_before,
                                requested_quantity, requested_amount_cent, restock_quantity,
                                created_at, updated_at
                            ) values (
                                :afterSaleId, :orderItemId, :skuId, :orderQuantity,
                                :basis, :refundedBefore, :requestedQuantity, :requestedAmount,
                                0, :now, :now
                            )
                            """)
                    .param("afterSaleId", afterSaleId)
                    .param("orderItemId", item.orderItemId())
                    .param("skuId", snapshot.skuId())
                    .param("orderQuantity", snapshot.quantity())
                    .param("basis", snapshot.paidAmountBasisCent())
                    .param("refundedBefore", snapshot.refundedQuantity())
                    .param("requestedQuantity", item.quantity())
                    .param("requestedAmount", item.requestedAmountCent())
                    .param("now", now)
                    .update();
            requireOne(updated);
        }
    }

    private OrderSnapshot requireOwnedOrder(long orderId, long userId, boolean forUpdate) {
        String suffix = forUpdate ? " for update" : "";
        return jdbcClient.sql("""
                        select id, order_no, user_id, status, paid_amount_cent,
                               refunded_amount_cent, shipped_at
                        from shop_order
                        where id = :orderId and user_id = :userId
                        """ + suffix)
                .param("orderId", orderId)
                .param("userId", userId)
                .query((rs, rowNum) -> new OrderSnapshot(
                        rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                        rs.getString("status"), rs.getLong("paid_amount_cent"),
                        rs.getLong("refunded_amount_cent"),
                        rs.getObject("shipped_at", LocalDateTime.class)))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private Route requireOwnedRoute(long afterSaleId, long userId) {
        return jdbcClient.sql("""
                        select id, order_id from after_sale_request
                        where id = :id and user_id = :userId
                        """)
                .param("id", afterSaleId)
                .param("userId", userId)
                .query((rs, rowNum) -> new Route(rs.getLong("id"), rs.getLong("order_id")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private AfterSaleState requireAfterSaleForUpdate(long afterSaleId, long userId) {
        return jdbcClient.sql("""
                        select id, order_id, after_sale_type, status, return_deadline_at
                        from after_sale_request
                        where id = :id and user_id = :userId
                        for update
                        """)
                .param("id", afterSaleId)
                .param("userId", userId)
                .query((rs, rowNum) -> new AfterSaleState(
                        rs.getLong("id"), rs.getLong("order_id"),
                        rs.getString("after_sale_type"), rs.getString("status"),
                        rs.getObject("return_deadline_at", LocalDateTime.class)))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private void lockAfterSales(long orderId) {
        jdbcClient.sql("""
                        select id from after_sale_request
                        where order_id = :orderId order by id for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
    }

    private Long activeAfterSaleId(long orderId) {
        return jdbcClient.sql("""
                        select id from after_sale_request
                        where order_id = :orderId and status in (:statuses)
                        order by id desc limit 1
                        """)
                .param("orderId", orderId)
                .param("statuses", ACTIVE_STATUSES)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private ExistingRequest existingRequest(long userId, long orderId, String requestKey) {
        return jdbcClient.sql("""
                        select id, request_digest from after_sale_request
                        where user_id = :userId and order_id = :orderId and request_key = :requestKey
                        """)
                .param("userId", userId)
                .param("orderId", orderId)
                .param("requestKey", requestKey)
                .query((rs, rowNum) -> new ExistingRequest(
                        rs.getLong("id"), rs.getString("request_digest")))
                .optional()
                .orElse(null);
    }

    private AfterSaleResponse requireDecorated(long afterSaleId, long userId) {
        AfterSaleResponse base = jdbcClient.sql("""
                        select asr.id, asr.after_sale_no, asr.order_id, o.order_no,
                               asr.user_id, u.nickname as user_nickname,
                               asr.after_sale_type, asr.status, asr.reason, asr.description,
                               asr.requested_amount_cent, asr.approved_amount_cent,
                               asr.audit_note, asr.reviewed_by, asr.reviewed_at, asr.created_at
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        left join app_user u on u.id = asr.user_id
                        where asr.id = :id and asr.user_id = :userId
                        """)
                .param("id", afterSaleId)
                .param("userId", userId)
                .query((rs, rowNum) -> new AfterSaleResponse(
                        rs.getLong("id"), rs.getString("after_sale_no"),
                        rs.getLong("order_id"), rs.getString("order_no"),
                        rs.getLong("user_id"), rs.getString("user_nickname"),
                        rs.getString("after_sale_type"), rs.getString("status"),
                        rs.getString("reason"), rs.getString("description"),
                        rs.getLong("requested_amount_cent"),
                        rs.getObject("approved_amount_cent", Long.class),
                        rs.getString("audit_note"), rs.getObject("reviewed_by", Long.class),
                        rs.getObject("reviewed_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        evidenceIdsFor(afterSaleId), List.of(), null))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return readService.decorate(base);
    }

    private List<Long> evidenceIdsFor(long afterSaleId) {
        return jdbcClient.sql("""
                        select file_id from after_sale_evidence
                        where after_sale_id = :id order by sort_order, id
                        """)
                .param("id", afterSaleId)
                .query(Long.class)
                .list();
    }

    private void validateEvidence(long userId, long orderId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        Long count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset asset
                        where asset.id in (:ids)
                          and asset.expires_at > current_timestamp
                          and asset.scope = :scope
                          and asset.media_kind = :mediaKind
                          and asset.visibility = 'PRIVATE'
                          and asset.status = 'ACTIVE'
                          and asset.uploaded_by_type = 'APP'
                          and asset.uploaded_by_id = :userId
                          and asset.upload_context_type = 'ORDER'
                          and asset.upload_context_id = :orderId
                          and not exists (
                              select 1 from storage_asset_usage usage_ref
                              where usage_ref.asset_id = asset.id and usage_ref.status = 'ACTIVE'
                          )
                        """)
                .param("ids", fileIds)
                .param("scope", StorageAssetScope.ATTACHMENT.name())
                .param("mediaKind", StorageMediaKind.IMAGE.name())
                .param("userId", userId)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (count != fileIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    private void insertEvidence(long afterSaleId, List<Long> fileIds) {
        for (int index = 0; index < fileIds.size(); index++) {
            jdbcClient.sql("""
                            insert into after_sale_evidence (after_sale_id, file_id, sort_order)
                            values (:afterSaleId, :fileId, :sortOrder)
                            """)
                    .param("afterSaleId", afterSaleId)
                    .param("fileId", fileIds.get(index))
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private void protectEvidence(
            long afterSaleId,
            String afterSaleNo,
            String orderNo,
            List<Long> fileIds
    ) {
        for (int index = 0; index < fileIds.size(); index++) {
            storageUsageService.addProtectedUsage(
                    fileIds.get(index), StorageFileUsageType.AFTER_SALE_EVIDENCE,
                    StorageUsageOwnerType.AFTER_SALE, afterSaleId,
                    "售后 " + afterSaleNo + " / 订单 " + orderNo, "", index + 1);
        }
    }

    private void claimEvidence(List<Long> fileIds) {
        if (!fileIds.isEmpty()) {
            jdbcClient.sql("""
                            update storage_asset set expires_at = null, updated_at = current_timestamp
                            where id in (:ids)
                            """)
                    .param("ids", fileIds)
                    .update();
        }
    }

    private List<AfterSaleItemRequest> normalizeItems(List<AfterSaleItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (AfterSaleItemRequest item : items) {
            if (item == null || item.orderItemId() == null || item.orderItemId() <= 0
                    || item.quantity() == null || item.quantity() <= 0
                    || quantities.putIfAbsent(item.orderItemId(), item.quantity()) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        return quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AfterSaleItemRequest(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<Long> evidenceIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(new LinkedHashSet<>(ids));
        if (result.size() > 3 || result.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return List.copyOf(result);
    }

    private String requestDigest(
            AfterSaleType type,
            List<AfterSaleItemRequest> items,
            String reason,
            String description,
            List<Long> evidenceIds
    ) {
        String itemText = normalizeItems(items).stream()
                .map(item -> item.orderItemId() + ":" + item.quantity())
                .reduce((left, right) -> left + "," + right).orElse("");
        return sha256(type.name() + "|" + itemText + "|" + reason + "|" + description + "|" + evidenceIds);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private AfterSaleType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return AfterSaleType.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private String optionalKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 80) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String requireText(String value, int maxLength) {
        String normalized = text(value, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String text(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private void requireOne(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private RawItem mapRawItem(ResultSet rs, int rowNum) throws SQLException {
        return new RawItem(
                rs.getLong("id"), rs.getLong("sku_id"), rs.getString("product_title"),
                rs.getString("spec_text"), rs.getString("image"), rs.getInt("quantity"),
                rs.getInt("refunded_quantity"), rs.getLong("line_amount_cent"),
                rs.getObject("paid_amount_allocated_cent", Long.class));
    }

    private record OrderSnapshot(
            long orderId,
            String orderNo,
            long userId,
            String status,
            long paidAmountCent,
            long refundedAmountCent,
            LocalDateTime shippedAt
    ) {
    }

    private record RawItem(
            long orderItemId,
            long skuId,
            String title,
            String specText,
            String image,
            int quantity,
            int refundedQuantity,
            long lineAmountCent,
            Long paidAmountAllocatedCent
    ) {
    }

    private record ItemSnapshot(
            long orderItemId,
            long skuId,
            String title,
            String specText,
            String image,
            int quantity,
            int refundedQuantity,
            long lineAmountCent,
            long paidAmountBasisCent
    ) {
    }

    private record Share(int index, long amount, BigInteger remainder) {
    }

    private record ExistingRequest(long afterSaleId, String requestDigest) {
    }

    private record Route(long afterSaleId, long orderId) {
    }

    private record AfterSaleState(
            long afterSaleId,
            long orderId,
            String type,
            String status,
            LocalDateTime returnDeadlineAt
    ) {
    }
}
