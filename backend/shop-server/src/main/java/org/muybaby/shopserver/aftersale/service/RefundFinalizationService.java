package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.OrderRefundStatus;
import org.muybaby.shopserver.aftersale.RefundOrderStatus;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.muybaby.shopserver.payment.config.PaymentConfigIdentityValidator;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class RefundFinalizationService {

    private static final Set<String> REOPENABLE_FAILED_PROVIDER_STATUSES = Set.of(
            "ABNORMAL", "MANUAL_INTERVENTION", "SUBMISSION_REJECTED");

    private final JdbcClient jdbcClient;
    private final OrderStatusLogService orderStatusLogService;
    private final PaymentConfigIdentityValidator paymentConfigIdentityValidator;
    private final RefundInventoryRestockService refundInventoryRestockService;
    private final AfterSaleStatusLogService afterSaleStatusLogService;

    public RefundFinalizationService(
            JdbcClient jdbcClient,
            OrderStatusLogService orderStatusLogService,
            PaymentConfigIdentityValidator paymentConfigIdentityValidator,
            RefundInventoryRestockService refundInventoryRestockService,
            AfterSaleStatusLogService afterSaleStatusLogService
    ) {
        this.jdbcClient = jdbcClient;
        this.orderStatusLogService = orderStatusLogService;
        this.paymentConfigIdentityValidator = paymentConfigIdentityValidator;
        this.refundInventoryRestockService = refundInventoryRestockService;
        this.afterSaleStatusLogService = afterSaleStatusLogService;
    }

    @Transactional
    public Outcome apply(ProviderRefundState providerState, ResolvedPaymentConfig verifiedConfig) {
        return apply(providerState, verifiedConfig, null);
    }

    @Transactional
    public Outcome applyClaimed(
            ProviderRefundState providerState,
            ResolvedPaymentConfig verifiedConfig,
            String recoveryClaimToken
    ) {
        if (!StringUtils.hasText(recoveryClaimToken)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return apply(providerState, verifiedConfig, recoveryClaimToken);
    }

    /**
     * Finalizes a refund request that WeChat explicitly rejected before creating a provider refund.
     * Unlike a timeout or transport failure, this is not an unknown result and must not remain
     * presented as a refund already submitted to the channel.
     */
    @Transactional
    public Outcome rejectSubmission(
            String outRefundNo,
            String providerErrorCode,
            ResolvedPaymentConfig verifiedConfig
    ) {
        return rejectSubmission(outRefundNo, providerErrorCode, verifiedConfig, null);
    }

    @Transactional
    public Outcome rejectClaimedSubmission(
            String outRefundNo,
            String providerErrorCode,
            ResolvedPaymentConfig verifiedConfig,
            String recoveryClaimToken
    ) {
        if (!StringUtils.hasText(recoveryClaimToken)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return rejectSubmission(outRefundNo, providerErrorCode, verifiedConfig, recoveryClaimToken);
    }

    private Outcome rejectSubmission(
            String outRefundNo,
            String providerErrorCode,
            ResolvedPaymentConfig verifiedConfig,
            String expectedRecoveryClaimToken
    ) {
        RefundRoute route = findRoute(outRefundNo);
        lockOrder(route.orderId());
        lockAfterSale(route.afterSaleId());
        RefundState refund = findForUpdate(outRefundNo);
        if (!route.refundOrderId().equals(refund.id())
                || !route.afterSaleId().equals(refund.afterSaleId())
                || !route.orderId().equals(refund.orderId())
                || !refund.id().equals(findLatestRefundOrderId(refund.afterSaleId()))) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        paymentConfigIdentityValidator.validate(
                refund.paymentConfigId(), refund.paymentConfigFingerprint(), verifiedConfig);
        if (RefundOrderStatus.SUCCESS.name().equals(refund.status())) {
            return Outcome.DUPLICATE;
        }
        if (!RefundOrderStatus.PROCESSING.name().equals(refund.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (StringUtils.hasText(expectedRecoveryClaimToken)) {
            if (!expectedRecoveryClaimToken.equals(refund.recoveryClaimToken())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        } else if (StringUtils.hasText(refund.recoveryClaimToken())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        String errorCode = normalizeStatus(providerErrorCode);
        ProviderRefundState rejectedState = new ProviderRefundState(
                refund.outRefundNo(), "", refund.outTradeNo(), "SUBMISSION_REJECTED",
                refund.refundAmountCent(), null, "");
        return markFailed(
                refund,
                rejectedState,
                "SUBMISSION_REJECTED",
                errorCode,
                "refund request rejected by provider: " + errorCode
        );
    }

    private Outcome apply(
            ProviderRefundState providerState,
            ResolvedPaymentConfig verifiedConfig,
            String expectedRecoveryClaimToken
    ) {
        RefundRoute route = findRoute(providerState.outRefundNo());
        lockOrder(route.orderId());
        lockAfterSale(route.afterSaleId());
        RefundState refund = findForUpdate(providerState.outRefundNo());
        if (!route.refundOrderId().equals(refund.id())
                || !route.afterSaleId().equals(refund.afterSaleId())
                || !route.orderId().equals(refund.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        paymentConfigIdentityValidator.validate(
                refund.paymentConfigId(), refund.paymentConfigFingerprint(), verifiedConfig);
        if (StringUtils.hasText(expectedRecoveryClaimToken)) {
            if (!expectedRecoveryClaimToken.equals(refund.recoveryClaimToken())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
        } else if (StringUtils.hasText(refund.recoveryClaimToken())) {
            // Provider reconciliation owns this refund until it has applied its query result and
            // released the lease. A callback will be retried (or discovered by the next query)
            // instead of racing a query/resubmit operation between NOT_FOUND and requestRefund.
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        Long latestRefundOrderId = findLatestRefundOrderId(refund.afterSaleId());
        if (!refund.id().equals(latestRefundOrderId)) {
            if (RefundOrderStatus.SUCCESS.name().equals(refund.status())) {
                return Outcome.DUPLICATE;
            }
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        validate(refund, providerState);
        String providerStatus = normalizeStatus(providerState.status());

        if (RefundOrderStatus.SUCCESS.name().equals(refund.status())) {
            return Outcome.DUPLICATE;
        }
        if (RefundOrderStatus.FAILED.name().equals(refund.status())
                && "CLOSED".equals(providerStatus)
                && !"CLOSED".equals(refund.callbackStatus())) {
            return refreshClosedFailure(refund, providerState, providerStatus);
        }
        if (RefundOrderStatus.FAILED.name().equals(refund.status())
                && !"SUCCESS".equals(providerStatus)
                && !("PROCESSING".equals(providerStatus)
                && REOPENABLE_FAILED_PROVIDER_STATUSES.contains(refund.callbackStatus()))) {
            return Outcome.DUPLICATE;
        }
        return switch (providerStatus) {
            case "SUCCESS" -> markSuccess(refund, providerState, providerStatus);
            case "PROCESSING" -> RefundOrderStatus.FAILED.name().equals(refund.status())
                    ? reopenProcessing(refund, providerState, providerStatus)
                    : markProcessing(refund, providerState, providerStatus);
            case "CLOSED", "ABNORMAL" -> markFailed(refund, providerState, providerStatus);
            default -> markProcessing(refund, providerState, providerStatus);
        };
    }

    private RefundRoute findRoute(String outRefundNo) {
        return jdbcClient.sql("""
                        select id, after_sale_id, order_id
                        from refund_order
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", outRefundNo)
                .query((rs, rowNum) -> new RefundRoute(
                        rs.getLong("id"), rs.getLong("after_sale_id"), rs.getLong("order_id")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.WECHAT_REFUND_FAILED));
    }

    private void lockAfterSale(Long afterSaleId) {
        boolean present = jdbcClient.sql("""
                        select id
                        from after_sale_request
                        where id = :afterSaleId
                        for update
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!present) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void lockOrder(Long orderId) {
        boolean present = jdbcClient.sql("""
                        select id
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!present) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private RefundState findForUpdate(String outRefundNo) {
        return jdbcClient.sql("""
                        select ro.id,
                               ro.after_sale_id,
                               ro.order_id,
                               ro.out_refund_no,
                               ro.refund_amount_cent,
                               ro.status,
                               ro.callback_status,
                               ro.restock_required,
                               ro.restocked_at,
                               ro.recovery_claim_token,
                               o.status as order_status,
                               o.paid_amount_cent,
                               o.refunded_amount_cent,
                               po.payment_config_id,
                               po.payment_config_fingerprint,
                               po.out_trade_no
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
                        join shop_order o on o.id = ro.order_id
                        where ro.out_refund_no = :outRefundNo
                        for update
                        """)
                .param("outRefundNo", outRefundNo)
                .query(this::mapRefundState)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.WECHAT_REFUND_FAILED));
    }

    private Long findLatestRefundOrderId(Long afterSaleId) {
        return jdbcClient.sql("""
                        select id
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id desc
                        limit 1
                        for update
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private void validate(RefundState refund, ProviderRefundState providerState) {
        if (!refund.outRefundNo().equals(providerState.outRefundNo())
                || !refund.outTradeNo().equals(providerState.outTradeNo())
                || refund.refundAmountCent() != providerState.refundAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private Outcome markProcessing(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        int updated = jdbcClient.sql("""
                        update refund_order
                        set refund_id = case when :refundId = '' then refund_id else :refundId end,
                            callback_status = :callbackStatus,
                            callback_digest = case when :callbackDigest = '' then callback_digest else :callbackDigest end,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :updatedAt
                        where id = :refundOrderId
                          and status = :expectedStatus
                        """)
                .param("refundId", nullToEmpty(providerState.refundId()))
                .param("callbackStatus", providerStatus)
                .param("callbackDigest", nullToEmpty(providerState.callbackDigest()))
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("refundOrderId", refund.id())
                .param("expectedStatus", RefundOrderStatus.PROCESSING.name())
                .update();
        requireUpdated(updated, "refund processing state");
        return Outcome.PROCESSING;
    }

    private Outcome reopenProcessing(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int refundRows = jdbcClient.sql("""
                        update refund_order
                        set status = :status,
                            failed_at = null,
                            refund_id = case when :refundId = '' then refund_id else :refundId end,
                            callback_status = :callbackStatus,
                            callback_digest = case when :callbackDigest = '' then callback_digest else :callbackDigest end,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :updatedAt
                        where id = :refundOrderId
                          and status = :expectedStatus
                          and callback_status in (:reopenableStatuses)
                        """)
                .param("status", RefundOrderStatus.PROCESSING.name())
                .param("refundId", nullToEmpty(providerState.refundId()))
                .param("callbackStatus", providerStatus)
                .param("callbackDigest", nullToEmpty(providerState.callbackDigest()))
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .param("expectedStatus", RefundOrderStatus.FAILED.name())
                .param("reopenableStatuses", REOPENABLE_FAILED_PROVIDER_STATUSES)
                .update();
        requireUpdated(refundRows, "refund resumed processing state");

        int afterSaleRows = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                          and status = :expectedStatus
                        """)
                .param("status", AfterSaleStatus.REFUNDING.name())
                .param("updatedAt", now)
                .param("afterSaleId", refund.afterSaleId())
                .param("expectedStatus", AfterSaleStatus.REFUND_FAILED.name())
                .update();
        requireUpdated(afterSaleRows, "after-sale resumed refunding state");
        long refundAfter = refund.refundedAmountCent() + refund.refundAmountCent();
        if (refundAfter > refund.paidAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        requireUpdated(jdbcClient.sql("""
                            update shop_order
                            set refund_status = :refundStatus, updated_at = :now
                            where id = :orderId
                              and status = :orderStatus
                              and refunded_amount_cent = :refundedAmountCent
                            """)
                    .param("refundStatus", refundAfter == refund.paidAmountCent()
                            ? OrderRefundStatus.FULL_REFUNDING.name()
                            : OrderRefundStatus.PARTIAL_REFUNDING.name())
                    .param("now", now)
                    .param("orderId", refund.orderId())
                    .param("orderStatus", refund.orderStatus())
                    .param("refundedAmountCent", refund.refundedAmountCent())
                    .update(), "order resumed refunding state");
        afterSaleStatusLogService.record(
                refund.afterSaleId(), AfterSaleStatus.REFUND_FAILED.name(),
                AfterSaleStatus.REFUNDING.name(), "REFUND_RECOVERY_RESUMED",
                "SYSTEM", null, "渠道确认退款仍在处理中", now);
        orderStatusLogService.record(
                refund.orderId(), refund.afterSaleId(), refund.orderStatus(), refund.orderStatus(),
                "REFUND_RECOVERY_RESUMED", "SYSTEM", null,
                "渠道确认退款仍在处理中", now);
        return Outcome.PROCESSING;
    }

    private Outcome markSuccess(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime successAt = providerState.successAt() == null ? now : providerState.successAt();
        refundInventoryRestockService.restockIfRequired(
                refund.id(), refund.orderId(), refund.restockRequired(), now);
        applyItemRefundSuccess(refund);
        int refundRows = jdbcClient.sql("""
                        update refund_order
                        set status = :status,
                            failed_at = null,
                            refund_id = case when :refundId = '' then refund_id else :refundId end,
                            callback_status = :callbackStatus,
                            callback_digest = case when :callbackDigest = '' then callback_digest else :callbackDigest end,
                            last_error_code = '',
                            last_error_message = '',
                            success_at = :successAt,
                            updated_at = :updatedAt
                        where id = :refundOrderId
                          and status in (:expectedStatuses)
                        """)
                .param("status", RefundOrderStatus.SUCCESS.name())
                .param("refundId", nullToEmpty(providerState.refundId()))
                .param("callbackStatus", providerStatus)
                .param("callbackDigest", nullToEmpty(providerState.callbackDigest()))
                .param("successAt", successAt)
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .param("expectedStatuses", java.util.List.of(
                        RefundOrderStatus.PROCESSING.name(), RefundOrderStatus.FAILED.name()))
                .update();
        requireUpdated(refundRows, "refund success state");

        int afterSaleRows = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                          and status in (:expectedStatuses)
                        """)
                .param("status", AfterSaleStatus.REFUNDED.name())
                .param("updatedAt", now)
                .param("afterSaleId", refund.afterSaleId())
                .param("expectedStatuses", java.util.List.of(
                        AfterSaleStatus.REFUNDING.name(), AfterSaleStatus.REFUND_FAILED.name()))
                .update();
        requireUpdated(afterSaleRows, "after-sale refunded state");
        long refundedAmountCent;
        try {
            refundedAmountCent = Math.addExact(
                    refund.refundedAmountCent(), refund.refundAmountCent());
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (refundedAmountCent > refund.paidAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        boolean fullyRefunded = refundedAmountCent == refund.paidAmountCent();
        String targetOrderStatus = fullyRefunded
                ? OrderStatus.REFUNDED.name() : refund.orderStatus();
        int orderRows = jdbcClient.sql("""
                            update shop_order
                            set status = :status,
                                refund_status = :refundStatus,
                                refunded_amount_cent = :refundedAmountCent,
                                last_refund_success_at = :successAt,
                                refunded_at = case when :fullyRefunded then :successAt else refunded_at end,
                                updated_at = :updatedAt
                            where id = :orderId
                              and status = :expectedStatus
                              and refunded_amount_cent = :expectedRefundedAmountCent
                            """)
                    .param("status", targetOrderStatus)
                    .param("refundStatus", fullyRefunded
                            ? OrderRefundStatus.FULLY_REFUNDED.name()
                            : OrderRefundStatus.PARTIALLY_REFUNDED.name())
                    .param("refundedAmountCent", refundedAmountCent)
                    .param("successAt", successAt)
                    .param("fullyRefunded", fullyRefunded)
                    .param("updatedAt", now)
                    .param("orderId", refund.orderId())
                    .param("expectedStatus", refund.orderStatus())
                    .param("expectedRefundedAmountCent", refund.refundedAmountCent())
                    .update();
        requireUpdated(orderRows, "order partial refund state");
        afterSaleStatusLogService.record(
                refund.afterSaleId(), AfterSaleStatus.REFUNDING.name(),
                AfterSaleStatus.REFUNDED.name(), "REFUND_SUCCEEDED",
                "WECHAT", null, "微信退款成功", successAt);
        orderStatusLogService.record(
                refund.orderId(), refund.afterSaleId(), refund.orderStatus(), targetOrderStatus,
                "REFUND_SUCCEEDED", "WECHAT", null, "微信退款成功", successAt);
        return Outcome.SUCCESS;
    }

    private void applyItemRefundSuccess(RefundState refund) {
        java.util.List<RefundedItemState> items = jdbcClient.sql("""
                        select asi.order_item_id, asi.refunded_quantity_before,
                               asi.approved_quantity, oi.quantity, oi.refunded_quantity
                        from after_sale_item asi
                        join order_item oi on oi.id = asi.order_item_id
                        where asi.after_sale_id = :afterSaleId
                          and asi.approved_quantity > 0
                        order by oi.id
                        for update
                        """)
                .param("afterSaleId", refund.afterSaleId())
                .query((rs, rowNum) -> new RefundedItemState(
                        rs.getLong("order_item_id"),
                        rs.getInt("refunded_quantity_before"),
                        rs.getInt("approved_quantity"),
                        rs.getInt("quantity"),
                        rs.getInt("refunded_quantity")))
                .list();
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        for (RefundedItemState item : items) {
            int refundedQuantity;
            try {
                refundedQuantity = Math.addExact(item.refundedQuantity(), item.approvedQuantity());
            } catch (ArithmeticException exception) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            if (item.refundedQuantity() != item.refundedQuantityBefore()
                    || item.approvedQuantity() <= 0
                    || refundedQuantity > item.orderQuantity()) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            int updated = jdbcClient.sql("""
                            update order_item
                            set refunded_quantity = :refundedQuantity
                            where id = :orderItemId
                              and refunded_quantity = :expectedRefundedQuantity
                            """)
                    .param("refundedQuantity", refundedQuantity)
                    .param("orderItemId", item.orderItemId())
                    .param("expectedRefundedQuantity", item.refundedQuantity())
                    .update();
            requireUpdated(updated, "order item refunded quantity");
        }
    }

    private Outcome markFailed(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        return markFailed(
                refund,
                providerState,
                providerStatus,
                providerStatus,
                "refund provider status " + providerStatus
        );
    }

    private Outcome markFailed(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus,
            String errorCode,
            String errorMessage
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int refundRows = jdbcClient.sql("""
                        update refund_order
                        set status = :status,
                            failed_at = :failedAt,
                            refund_id = case when :refundId = '' then refund_id else :refundId end,
                            callback_status = :callbackStatus,
                            callback_digest = case when :callbackDigest = '' then callback_digest else :callbackDigest end,
                            last_error_code = :lastErrorCode,
                            last_error_message = :lastErrorMessage,
                            updated_at = :updatedAt
                        where id = :refundOrderId
                          and status = :expectedStatus
                        """)
                .param("status", RefundOrderStatus.FAILED.name())
                .param("failedAt", now)
                .param("refundId", nullToEmpty(providerState.refundId()))
                .param("callbackStatus", providerStatus)
                .param("callbackDigest", nullToEmpty(providerState.callbackDigest()))
                .param("lastErrorCode", errorCode)
                .param("lastErrorMessage", errorMessage)
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .param("expectedStatus", RefundOrderStatus.PROCESSING.name())
                .update();
        requireUpdated(refundRows, "refund failed state");

        int afterSaleRows = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                          and status = :expectedStatus
                        """)
                .param("status", AfterSaleStatus.REFUND_FAILED.name())
                .param("updatedAt", now)
                .param("afterSaleId", refund.afterSaleId())
                .param("expectedStatus", AfterSaleStatus.REFUNDING.name())
                .update();
        requireUpdated(afterSaleRows, "after-sale refund failure state");
        requireUpdated(jdbcClient.sql("""
                            update shop_order
                            set refund_status = :refundStatus, updated_at = :now
                            where id = :orderId
                              and status = :orderStatus
                              and refunded_amount_cent = :refundedAmountCent
                            """)
                    .param("refundStatus", OrderRefundStatus.REFUND_FAILED.name())
                    .param("now", now)
                    .param("orderId", refund.orderId())
                    .param("orderStatus", refund.orderStatus())
                    .param("refundedAmountCent", refund.refundedAmountCent())
                    .update(), "order refund failure state");
        afterSaleStatusLogService.record(
                refund.afterSaleId(), AfterSaleStatus.REFUNDING.name(),
                AfterSaleStatus.REFUND_FAILED.name(), "REFUND_FAILED",
                "WECHAT", null, "微信退款失败：" + errorCode, now);
        orderStatusLogService.record(
                refund.orderId(), refund.afterSaleId(), refund.orderStatus(), refund.orderStatus(),
                "REFUND_FAILED", "WECHAT", null, "微信退款失败：" + errorCode, now);
        return Outcome.FAILED;
    }

    private Outcome refreshClosedFailure(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                        update refund_order
                        set failed_at = coalesce(failed_at, :failedAt),
                            refund_id = case when :refundId = '' then refund_id else :refundId end,
                            callback_status = :callbackStatus,
                            callback_digest = case when :callbackDigest = '' then callback_digest else :callbackDigest end,
                            last_error_code = :lastErrorCode,
                            last_error_message = :lastErrorMessage,
                            updated_at = :updatedAt
                        where id = :refundOrderId
                          and status = :expectedStatus
                          and callback_status <> :callbackStatus
                        """)
                .param("failedAt", now)
                .param("refundId", nullToEmpty(providerState.refundId()))
                .param("callbackStatus", providerStatus)
                .param("callbackDigest", nullToEmpty(providerState.callbackDigest()))
                .param("lastErrorCode", providerStatus)
                .param("lastErrorMessage", "refund provider status " + providerStatus)
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .param("expectedStatus", RefundOrderStatus.FAILED.name())
                .update();
        requireUpdated(updated, "refund closed failure refresh");
        return Outcome.FAILED;
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private void requireUpdated(int updatedRows, String transition) {
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private RefundState mapRefundState(ResultSet rs, int rowNum) throws SQLException {
        return new RefundState(
                rs.getLong("id"),
                rs.getLong("after_sale_id"),
                rs.getLong("order_id"),
                rs.getString("out_refund_no"),
                rs.getLong("refund_amount_cent"),
                rs.getString("status"),
                rs.getString("callback_status"),
                rs.getBoolean("restock_required"),
                rs.getObject("restocked_at", LocalDateTime.class),
                rs.getString("recovery_claim_token"),
                rs.getString("order_status"),
                rs.getLong("paid_amount_cent"),
                rs.getLong("refunded_amount_cent"),
                rs.getObject("payment_config_id", Long.class),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no")
        );
    }

    public enum Outcome {
        SUCCESS,
        PROCESSING,
        FAILED,
        DUPLICATE
    }

    public record ProviderRefundState(
            String outRefundNo,
            String refundId,
            String outTradeNo,
            String status,
            long refundAmountCent,
            LocalDateTime successAt,
            String callbackDigest
    ) {
    }

    private record RefundState(
            Long id,
            Long afterSaleId,
            Long orderId,
            String outRefundNo,
            long refundAmountCent,
            String status,
            String callbackStatus,
            boolean restockRequired,
            LocalDateTime restockedAt,
            String recoveryClaimToken,
            String orderStatus,
            long paidAmountCent,
            long refundedAmountCent,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo
    ) {
    }

    private record RefundRoute(Long refundOrderId, Long afterSaleId, Long orderId) {
    }

    private record RefundedItemState(
            long orderItemId,
            int refundedQuantityBefore,
            int approvedQuantity,
            int orderQuantity,
            int refundedQuantity
    ) {
    }
}
