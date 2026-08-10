package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.RefundOrderStatus;
import org.muybaby.shopserver.aftersale.RefundRecoveryProperties;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundQueryResult;
import org.muybaby.shopserver.payment.provider.WechatRefundRequest;
import org.muybaby.shopserver.payment.provider.WechatRefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RefundRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RefundRecoveryService.class);
    private static final int MAX_BATCH_SIZE = 200;
    private static final int MAX_CLAIM_CONTENTION_RETRIES = 8;

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentNotificationRouteService paymentNotificationRouteService;
    private final WechatPayProvider wechatPayProvider;
    private final RefundFinalizationService refundFinalizationService;
    private final RefundRecoveryProperties properties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public RefundRecoveryService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            PaymentNotificationRouteService paymentNotificationRouteService,
            WechatPayProvider wechatPayProvider,
            RefundFinalizationService refundFinalizationService,
            RefundRecoveryProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentNotificationRouteService = paymentNotificationRouteService;
        this.wechatPayProvider = wechatPayProvider;
        this.refundFinalizationService = refundFinalizationService;
        this.properties = properties;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public int recoverPendingRefunds() {
        return recoverPendingRefunds(properties.batchSize());
    }

    public int recoverPendingRefunds(int batchSize) {
        requireValidBatchSize(batchSize);
        Integer recovered = withoutTransaction.execute(status -> recoverOutsideTransaction(batchSize));
        return recovered == null ? 0 : recovered;
    }

    /**
     * Immediately queries the provider for one refund. This bypasses the scheduler delay, but it
     * still uses the normal recovery claim and finalization paths so a scheduler run, callback and
     * operator action cannot mutate the refund concurrently.
     */
    public ManualRecoveryResult queryRefundNow(Long refundOrderId) {
        return executeManualRecovery(refundOrderId, false);
    }

    /**
     * Safely retries an uncertain refund request. The provider is queried first and the original
     * merchant refund number is submitted again only when the provider confirms NOT_FOUND.
     */
    public ManualRecoveryResult resubmitRefundNow(Long refundOrderId) {
        return executeManualRecovery(refundOrderId, true);
    }

    public boolean isRecoveryClaimActive(String claimToken, LocalDateTime claimedAt) {
        return claimToken != null
                && !claimToken.isBlank()
                && claimedAt != null
                && claimedAt.isAfter(LocalDateTime.now(clock).minus(properties.claimTimeout()));
    }

    private ManualRecoveryResult executeManualRecovery(Long refundOrderId, boolean resubmitWhenMissing) {
        if (refundOrderId == null || refundOrderId < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ManualRecoveryResult result = withoutTransaction.execute(status -> {
            ClaimedRefund claimed = requiresNewTransaction.execute(
                    transactionStatus -> claimRefundForManualRecovery(
                            refundOrderId, resubmitWhenMissing));
            if (claimed == null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            try {
                ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                        claimed.paymentConfigId(), claimed.paymentConfigFingerprint());
                WechatRefundQueryResult providerResult = wechatPayProvider.queryRefund(
                        config, claimed.outRefundNo());
                boolean resubmitted = false;
                if ("NOT_FOUND".equalsIgnoreCase(providerResult.status())) {
                    if (!resubmitWhenMissing) {
                        if (!releaseClaimAfterManualQuery(claimed)) {
                            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
                        }
                        return new ManualRecoveryResult(
                                claimed.refundOrderId(), "NOT_FOUND", null, false);
                    }
                    providerResult = submitOriginalRefundRequest(config, claimed);
                    resubmitted = true;
                }

                RefundFinalizationService.Outcome outcome = applyProviderResult(
                        claimed, providerResult, config);
                if (outcome == null) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
                }
                return new ManualRecoveryResult(
                        claimed.refundOrderId(), providerResult.status(), outcome, resubmitted);
            } catch (RuntimeException ex) {
                recordQueryFailure(claimed, ex);
                throw ex;
            }
        });
        if (result == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return result;
    }

    private int recoverOutsideTransaction(int batchSize) {
        LocalDateTime scanTime = LocalDateTime.now(clock);
        if (!hasClaimableRefund(scanTime)) {
            return 0;
        }
        int recoveredCount = 0;
        for (int index = 0; index < batchSize; index++) {
            ClaimedRefund claimedRefund = claimNextRefund(scanTime);
            if (claimedRefund == null) {
                break;
            }
            try {
                ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                        claimedRefund.paymentConfigId(), claimedRefund.paymentConfigFingerprint());
                WechatRefundQueryResult providerResult = reconcileWithProvider(config, claimedRefund);
                if ("NOT_FOUND".equalsIgnoreCase(providerResult.status())) {
                    releaseClaimAfterProviderNotFound(claimedRefund);
                    continue;
                }
                RefundFinalizationService.Outcome outcome = applyProviderResult(
                        claimedRefund, providerResult, config);
                if (outcome != null && outcome != RefundFinalizationService.Outcome.DUPLICATE) {
                    recoveredCount++;
                }
            } catch (RuntimeException ex) {
                recordQueryFailure(claimedRefund, ex);
                log.warn("One refund could not be reconciled; it will be retried (type={})", safeErrorCode(ex));
            }
        }
        return recoveredCount;
    }

    /**
     * A failed HTTP response does not prove that WeChat accepted the original refund request. If
     * the subsequent query confirms that the merchant refund number does not exist, submitting the
     * exact same request again is the provider-supported idempotent recovery path.
     */
    private WechatRefundQueryResult reconcileWithProvider(
            ResolvedPaymentConfig config,
            ClaimedRefund claimedRefund
    ) {
        WechatRefundQueryResult queryResult = wechatPayProvider.queryRefund(
                config, claimedRefund.outRefundNo());
        if (!"NOT_FOUND".equalsIgnoreCase(queryResult.status())) {
            return queryResult;
        }

        // A PROCESSING row represents an indeterminate original request, for which reusing the
        // same merchant refund number is the idempotent crash-recovery path. FAILED rows selected
        // by the scheduler are ABNORMAL refunds: they may be queried for a later merchant-side
        // resolution, but a new provider submission requires an explicit administrator action.
        if (RefundOrderStatus.FAILED.name().equals(claimedRefund.status())) {
            return queryResult;
        }

        return submitOriginalRefundRequest(config, claimedRefund);
    }

    private WechatRefundQueryResult submitOriginalRefundRequest(
            ResolvedPaymentConfig config,
            ClaimedRefund claimedRefund
    ) {
        requireOriginalRefundSubmissionAllowed(claimedRefund);
        WechatRefundRequest retryRequest = new WechatRefundRequest(
                claimedRefund.outTradeNo(),
                claimedRefund.transactionId(),
                claimedRefund.outRefundNo(),
                claimedRefund.refundAmountCent(),
                claimedRefund.totalAmountCent(),
                claimedRefund.reason(),
                paymentNotificationRouteService.refundNotifyUrl(
                        config.refundNotifyUrl(), claimedRefund.notificationRouteToken())
        );
        WechatRefundResult retryResult = wechatPayProvider.requestRefund(config, retryRequest);
        return new WechatRefundQueryResult(
                claimedRefund.outRefundNo(),
                retryResult.refundId(),
                claimedRefund.outTradeNo(),
                retryResult.status(),
                claimedRefund.refundAmountCent(),
                null
        );
    }

    private ClaimedRefund claimRefundForManualRecovery(
            Long refundOrderId,
            boolean resubmitWhenMissing
    ) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        LocalDateTime expiredClaimBefore = claimedAt.minus(properties.claimTimeout());
        ManualClaimRoute route = jdbcClient.sql("""
                        select after_sale_id, order_id, payment_order_id
                        from refund_order
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", refundOrderId)
                .query((rs, rowNum) -> new ManualClaimRoute(
                        rs.getLong("after_sale_id"),
                        rs.getLong("order_id"),
                        rs.getLong("payment_order_id")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        lockManualClaimParents(route);
        RefundCandidate candidate = jdbcClient.sql("""
                        select ro.id,
                               ro.out_refund_no,
                               ro.refund_amount_cent,
                               ro.status,
                               ro.callback_status,
                               ro.recovery_attempts,
                               ro.notification_route_token,
                               po.payment_config_id,
                               po.payment_config_fingerprint,
                               po.out_trade_no,
                               po.transaction_id,
                               po.amount_cent as total_amount_cent,
                               ro.provider_reason,
                               asr.reason,
                               asr.audit_note
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
                        join after_sale_request asr on asr.id = ro.after_sale_id
                        where ro.id = :refundOrderId
                          and ro.after_sale_id = :afterSaleId
                          and ro.order_id = :orderId
                          and ro.payment_order_id = :paymentOrderId
                          and not exists (
                              select 1
                              from refund_order newer
                              where newer.after_sale_id = ro.after_sale_id
                                and newer.id > ro.id
                          )
                        for update
                        """)
                .param("refundOrderId", refundOrderId)
                .param("afterSaleId", route.afterSaleId())
                .param("orderId", route.orderId())
                .param("paymentOrderId", route.paymentOrderId())
                .query(this::mapRefundCandidate)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!RefundOrderStatus.PROCESSING.name().equals(candidate.status())
                && !RefundOrderStatus.FAILED.name().equals(candidate.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (resubmitWhenMissing && "CLOSED".equalsIgnoreCase(candidate.callbackStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        String claimToken = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = :claimToken,
                            recovery_claimed_at = :claimedAt,
                            recovery_attempts = recovery_attempts + 1
                        where id = :refundOrderId
                          and status = :status
                          and callback_status = :callbackStatus
                          and (
                              recovery_claim_token is null
                              or recovery_claimed_at is null
                              or recovery_claimed_at <= :expiredClaimBefore
                          )
                        """)
                .param("claimToken", claimToken)
                .param("claimedAt", claimedAt)
                .param("refundOrderId", candidate.refundOrderId())
                .param("status", candidate.status())
                .param("callbackStatus", candidate.callbackStatus())
                .param("expiredClaimBefore", expiredClaimBefore)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return new ClaimedRefund(
                candidate.refundOrderId(),
                candidate.outRefundNo(),
                candidate.paymentConfigId(),
                candidate.paymentConfigFingerprint(),
                candidate.notificationRouteToken(),
                claimToken,
                candidate.recoveryAttempts() + 1,
                candidate.status(),
                candidate.callbackStatus(),
                candidate.outTradeNo(),
                candidate.transactionId(),
                candidate.refundAmountCent(),
                candidate.totalAmountCent(),
                providerReason(candidate)
        );
    }

    private boolean hasClaimableRefund(LocalDateTime scanTime) {
        LocalDateTime staleBefore = scanTime.minus(properties.minAge());
        LocalDateTime expiredClaimBefore = scanTime.minus(properties.claimTimeout());
        return jdbcClient.sql("""
                        select ro.id
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
                        where (
                              ro.status = :processingStatus
                              or (ro.status = :failedStatus and ro.callback_status = :abnormalStatus)
                          )
                          and ro.updated_at <= :staleBefore
                          and (ro.next_recovery_at is null or ro.next_recovery_at <= :scanTime)
                          and (
                              ro.recovery_claim_token is null
                              or ro.recovery_claimed_at is null
                              or ro.recovery_claimed_at <= :expiredClaimBefore
                          )
                        order by ro.requested_at asc, ro.id asc
                        limit 1
                        """)
                .param("processingStatus", RefundOrderStatus.PROCESSING.name())
                .param("failedStatus", RefundOrderStatus.FAILED.name())
                .param("abnormalStatus", "ABNORMAL")
                .param("staleBefore", staleBefore)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    private ClaimedRefund claimNextRefund(LocalDateTime scanTime) {
        for (int attempt = 0; attempt < MAX_CLAIM_CONTENTION_RETRIES; attempt++) {
            ClaimedRefund claimed = requiresNewTransaction.execute(status -> claimNextRefundOnce(scanTime));
            if (claimed != null) {
                return claimed;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private ClaimedRefund claimNextRefundOnce(LocalDateTime scanTime) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        LocalDateTime staleBefore = scanTime.minus(properties.minAge());
        LocalDateTime expiredClaimBefore = claimedAt.minus(properties.claimTimeout());
        List<RefundCandidate> candidates = jdbcClient.sql("""
                        select ro.id,
                               ro.out_refund_no,
                               ro.refund_amount_cent,
                               ro.status,
                               ro.callback_status,
                               ro.recovery_attempts,
                               ro.notification_route_token,
                               po.payment_config_id,
                               po.payment_config_fingerprint,
                               po.out_trade_no,
                               po.transaction_id,
                               po.amount_cent as total_amount_cent,
                               ro.provider_reason,
                               asr.reason,
                               asr.audit_note
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
                        join after_sale_request asr on asr.id = ro.after_sale_id
                        where (
                              ro.status = :processingStatus
                              or (ro.status = :failedStatus and ro.callback_status = :abnormalStatus)
                          )
                          and ro.updated_at <= :staleBefore
                          and (ro.next_recovery_at is null or ro.next_recovery_at <= :scanTime)
                          and (
                              ro.recovery_claim_token is null
                              or ro.recovery_claimed_at is null
                              or ro.recovery_claimed_at <= :expiredClaimBefore
                          )
                        order by ro.requested_at asc, ro.id asc
                        limit 1
                        """)
                .param("processingStatus", RefundOrderStatus.PROCESSING.name())
                .param("failedStatus", RefundOrderStatus.FAILED.name())
                .param("abnormalStatus", "ABNORMAL")
                .param("staleBefore", staleBefore)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .query(this::mapRefundCandidate)
                .list();
        if (candidates.isEmpty()) {
            return null;
        }

        RefundCandidate candidate = candidates.getFirst();
        String claimToken = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = :claimToken,
                            recovery_claimed_at = :claimedAt,
                            recovery_attempts = recovery_attempts + 1
                        where id = :refundOrderId
                          and status = :status
                          and (:status <> :failedStatus or callback_status = :abnormalStatus)
                          and updated_at <= :staleBefore
                          and (next_recovery_at is null or next_recovery_at <= :scanTime)
                          and (
                              recovery_claim_token is null
                              or recovery_claimed_at is null
                              or recovery_claimed_at <= :expiredClaimBefore
                          )
                        """)
                .param("claimToken", claimToken)
                .param("claimedAt", claimedAt)
                .param("refundOrderId", candidate.refundOrderId())
                .param("status", candidate.status())
                .param("failedStatus", RefundOrderStatus.FAILED.name())
                .param("abnormalStatus", "ABNORMAL")
                .param("staleBefore", staleBefore)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .update();
        if (updated != 1) {
            return null;
        }
        return new ClaimedRefund(
                candidate.refundOrderId(),
                candidate.outRefundNo(),
                candidate.paymentConfigId(),
                candidate.paymentConfigFingerprint(),
                candidate.notificationRouteToken(),
                claimToken,
                candidate.recoveryAttempts() + 1,
                candidate.status(),
                candidate.callbackStatus(),
                candidate.outTradeNo(),
                candidate.transactionId(),
                candidate.refundAmountCent(),
                candidate.totalAmountCent(),
                providerReason(candidate)
        );
    }

    private RefundFinalizationService.Outcome applyProviderResult(
            ClaimedRefund claimed,
            WechatRefundQueryResult providerResult,
            ResolvedPaymentConfig verifiedConfig
    ) {
        return requiresNewTransaction.execute(status -> {
            RecoveryClaimState claimState = jdbcClient.sql("""
                            select status, recovery_claim_token
                            from refund_order
                            where id = :refundOrderId
                            """)
                    .param("refundOrderId", claimed.refundOrderId())
                    .query(this::mapRecoveryClaimState)
                    .optional()
                    .orElse(null);
            if (claimState == null
                    || !claimed.status().equals(claimState.status())
                    || !claimed.claimToken().equals(claimState.claimToken())) {
                clearClaimIfOwned(claimed, null);
                return null;
            }

            RefundFinalizationService.Outcome outcome = refundFinalizationService.applyClaimed(
                    new RefundFinalizationService.ProviderRefundState(
                            providerResult.outRefundNo(),
                            providerResult.refundId(),
                            providerResult.outTradeNo(),
                            providerResult.status(),
                            providerResult.refundAmountCent(),
                            providerResult.successAt(),
                            ""
                    ),
                    verifiedConfig,
                    claimed.claimToken()
            );
            LocalDateTime nextRecoveryAt = switch (outcome) {
                case PROCESSING -> LocalDateTime.now(clock).plus(properties.delay());
                case FAILED, DUPLICATE -> LocalDateTime.now(clock).plus(properties.maxRetryDelay());
                case SUCCESS -> null;
            };
            clearClaimIfOwned(claimed, nextRecoveryAt);
            return outcome;
        });
    }

    private boolean releaseClaimAfterManualQuery(ClaimedRefund claimed) {
        Boolean released = requiresNewTransaction.execute(status -> jdbcClient.sql("""
                                update refund_order
                                set recovery_claim_token = null,
                                    recovery_claimed_at = null
                                where id = :refundOrderId
                                  and recovery_claim_token = :claimToken
                                """)
                .param("refundOrderId", claimed.refundOrderId())
                .param("claimToken", claimed.claimToken())
                .update() == 1);
        return Boolean.TRUE.equals(released);
    }

    private void lockManualClaimParents(ManualClaimRoute route) {
        requireLock("""
                        select id
                        from shop_order
                        where id = :id
                        for update
                        """, route.orderId());
        requireLock("""
                        select id
                        from after_sale_request
                        where id = :id
                        for update
                        """, route.afterSaleId());
        requireLock("""
                        select id
                        from payment_order
                        where id = :id
                        for update
                        """, route.paymentOrderId());
    }

    private void requireLock(String sql, Long id) {
        if (id == null || jdbcClient.sql(sql)
                .param("id", id)
                .query(Long.class)
                .optional()
                .isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireOriginalRefundSubmissionAllowed(ClaimedRefund claimed) {
        Boolean allowed = requiresNewTransaction.execute(status -> {
            boolean currentOwner = jdbcClient.sql("""
                            select ro.id
                            from refund_order ro
                            where ro.id = :refundOrderId
                              and ro.status = :status
                              and ro.callback_status = :callbackStatus
                              and ro.callback_status <> 'CLOSED'
                              and ro.recovery_claim_token = :claimToken
                              and not exists (
                                  select 1
                                  from refund_order newer
                                  where newer.after_sale_id = ro.after_sale_id
                                    and newer.id > ro.id
                              )
                            for update
                            """)
                    .param("refundOrderId", claimed.refundOrderId())
                    .param("status", claimed.status())
                    .param("callbackStatus", claimed.callbackStatus())
                    .param("claimToken", claimed.claimToken())
                    .query(Long.class)
                    .optional()
                    .isPresent();
            if (!currentOwner) {
                return false;
            }
            return jdbcClient.sql("""
                            update refund_order
                            set recovery_claimed_at = :claimedAt
                            where id = :refundOrderId
                              and recovery_claim_token = :claimToken
                            """)
                    .param("claimedAt", LocalDateTime.now(clock))
                    .param("refundOrderId", claimed.refundOrderId())
                    .param("claimToken", claimed.claimToken())
                    .update() == 1;
        });
        if (!Boolean.TRUE.equals(allowed)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void releaseClaimAfterProviderNotFound(ClaimedRefund claimed) {
        requiresNewTransaction.executeWithoutResult(status -> clearClaimIfOwned(
                claimed, LocalDateTime.now(clock).plus(properties.maxRetryDelay())));
    }

    private void recordQueryFailure(ClaimedRefund claimed, RuntimeException failure) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> {
                LocalDateTime now = LocalDateTime.now(clock);
                jdbcClient.sql("""
                                update refund_order
                                set recovery_claim_token = null,
                                    recovery_claimed_at = null,
                                    next_recovery_at = :nextRecoveryAt,
                                    last_error_code = :errorCode,
                                    last_error_message = 'Refund status query failed; retry scheduled',
                                    updated_at = :updatedAt
                                where id = :refundOrderId
                                  and status = :status
                                  and recovery_claim_token = :claimToken
                                """)
                        .param("nextRecoveryAt", now.plus(retryDelay(claimed.recoveryAttempts())))
                        .param("errorCode", safeErrorCode(failure))
                        .param("updatedAt", now)
                        .param("refundOrderId", claimed.refundOrderId())
                        .param("status", claimed.status())
                        .param("claimToken", claimed.claimToken())
                        .update();
            });
        } catch (RuntimeException persistenceFailure) {
            log.warn("A refund recovery failure could not be recorded (type={})", safeErrorCode(persistenceFailure));
        }
    }

    private void clearClaimIfOwned(ClaimedRefund claimed, LocalDateTime nextRecoveryAt) {
        jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = null,
                            recovery_claimed_at = null,
                            next_recovery_at = :nextRecoveryAt
                        where id = :refundOrderId
                          and recovery_claim_token = :claimToken
                        """)
                .param("nextRecoveryAt", nextRecoveryAt)
                .param("refundOrderId", claimed.refundOrderId())
                .param("claimToken", claimed.claimToken())
                .update();
    }

    private Duration retryDelay(int recoveryAttempts) {
        int exponent = Math.min(Math.max(recoveryAttempts - 1, 0), 10);
        Duration candidate;
        try {
            candidate = properties.baseRetryDelay().multipliedBy(1L << exponent);
        } catch (ArithmeticException ex) {
            return properties.maxRetryDelay();
        }
        return candidate.compareTo(properties.maxRetryDelay()) > 0
                ? properties.maxRetryDelay()
                : candidate;
    }

    private String safeErrorCode(RuntimeException failure) {
        String simpleName = failure.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return "RuntimeException";
        }
        return simpleName.length() <= 64 ? simpleName : simpleName.substring(0, 64);
    }

    private void requireValidBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Refund recovery batch size must be between 1 and 200");
        }
    }

    private RefundCandidate mapRefundCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new RefundCandidate(
                rs.getLong("id"),
                rs.getString("out_refund_no"),
                nullableLong(rs, "payment_config_id"),
                rs.getString("payment_config_fingerprint"),
                rs.getString("notification_route_token"),
                rs.getString("status"),
                rs.getString("callback_status"),
                rs.getInt("recovery_attempts"),
                rs.getString("out_trade_no"),
                rs.getString("transaction_id"),
                rs.getLong("refund_amount_cent"),
                rs.getLong("total_amount_cent"),
                rs.getString("provider_reason"),
                rs.getString("reason"),
                rs.getString("audit_note")
        );
    }

    private String providerReason(RefundCandidate candidate) {
        return candidate.providerReason() != null
                ? candidate.providerReason()
                : firstNonBlank(candidate.auditNote(), candidate.reason());
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private RecoveryClaimState mapRecoveryClaimState(ResultSet rs, int rowNum) throws SQLException {
        return new RecoveryClaimState(
                rs.getString("status"),
                rs.getString("recovery_claim_token")
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private record RefundCandidate(
            Long refundOrderId,
            String outRefundNo,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String notificationRouteToken,
            String status,
            String callbackStatus,
            int recoveryAttempts,
            String outTradeNo,
            String transactionId,
            long refundAmountCent,
            long totalAmountCent,
            String providerReason,
            String reason,
            String auditNote
    ) {
    }

    private record ClaimedRefund(
            Long refundOrderId,
            String outRefundNo,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String notificationRouteToken,
            String claimToken,
            int recoveryAttempts,
            String status,
            String callbackStatus,
            String outTradeNo,
            String transactionId,
            long refundAmountCent,
            long totalAmountCent,
            String reason
    ) {
    }

    private record RecoveryClaimState(String status, String claimToken) {
    }

    private record ManualClaimRoute(Long afterSaleId, Long orderId, Long paymentOrderId) {
    }

    public record ManualRecoveryResult(
            Long refundOrderId,
            String providerStatus,
            RefundFinalizationService.Outcome outcome,
            boolean resubmitted
    ) {
    }
}
