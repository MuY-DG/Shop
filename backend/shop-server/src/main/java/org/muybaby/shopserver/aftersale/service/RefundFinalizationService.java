package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
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
            "ABNORMAL", "MANUAL_INTERVENTION");

    private final JdbcClient jdbcClient;
    private final OrderStatusLogService orderStatusLogService;
    private final PaymentConfigIdentityValidator paymentConfigIdentityValidator;

    public RefundFinalizationService(
            JdbcClient jdbcClient,
            OrderStatusLogService orderStatusLogService,
            PaymentConfigIdentityValidator paymentConfigIdentityValidator
    ) {
        this.jdbcClient = jdbcClient;
        this.orderStatusLogService = orderStatusLogService;
        this.paymentConfigIdentityValidator = paymentConfigIdentityValidator;
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

    private Outcome apply(
            ProviderRefundState providerState,
            ResolvedPaymentConfig verifiedConfig,
            String expectedRecoveryClaimToken
    ) {
        RefundRoute route = findRoute(providerState.outRefundNo());
        lockAfterSale(route.afterSaleId());
        lockOrder(route.orderId());
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
                               ro.recovery_claim_token,
                               po.payment_config_id,
                               po.payment_config_fingerprint,
                               po.out_trade_no
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
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
                .param("updatedAt", LocalDateTime.now())
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
        LocalDateTime now = LocalDateTime.now();
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

        orderStatusLogService.record(
                refund.orderId(), refund.afterSaleId(),
                OrderStatus.REFUNDING.name(), OrderStatus.REFUNDING.name(),
                "REFUND_RECOVERY_RESUMED", "SYSTEM", null,
                "渠道确认退款仍在处理中", now
        );
        return Outcome.PROCESSING;
    }

    private Outcome markSuccess(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime successAt = providerState.successAt() == null ? now : providerState.successAt();
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

        int orderRows = jdbcClient.sql("""
                        update shop_order
                        set status = :status,
                            refunded_at = :refundedAt,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = :expectedStatus
                        """)
                .param("status", OrderStatus.REFUNDED.name())
                .param("refundedAt", successAt)
                .param("updatedAt", now)
                .param("orderId", refund.orderId())
                .param("expectedStatus", OrderStatus.REFUNDING.name())
                .update();
        requireUpdated(orderRows, "order refunded state");

        orderStatusLogService.record(
                refund.orderId(), refund.afterSaleId(),
                OrderStatus.REFUNDING.name(), OrderStatus.REFUNDED.name(),
                "REFUND_SUCCEEDED", "WECHAT", null, "微信退款成功", successAt
        );
        return Outcome.SUCCESS;
    }

    private Outcome markFailed(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        LocalDateTime now = LocalDateTime.now();
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
                .param("lastErrorCode", providerStatus)
                .param("lastErrorMessage", "refund provider status " + providerStatus)
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .param("expectedStatus", RefundOrderStatus.PROCESSING.name())
                .update();
        requireUpdated(refundRows, "refund failed state");

        int afterSaleRows = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
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
        return Outcome.FAILED;
    }

    private Outcome refreshClosedFailure(
            RefundState refund,
            ProviderRefundState providerState,
            String providerStatus
    ) {
        LocalDateTime now = LocalDateTime.now();
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
                rs.getString("recovery_claim_token"),
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
            String recoveryClaimToken,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo
    ) {
    }

    private record RefundRoute(Long refundOrderId, Long afterSaleId, Long orderId) {
    }
}
