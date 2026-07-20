package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderCloseService;
import org.muybaby.shopserver.payment.PaymentInitiationProperties;
import org.muybaby.shopserver.payment.PaymentTimeoutScanProperties;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentTimeoutCloseService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutCloseService.class);
    private static final String OPERATOR_TYPE_SYSTEM = "SYSTEM";
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_CLAIM_CONTENTION_RETRIES = 8;

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;
    private final OrderCloseService orderCloseService;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentFinalizationService paymentFinalizationService;
    private final PaymentTimeoutScanProperties properties;
    private final PaymentInitiationProperties initiationProperties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public PaymentTimeoutCloseService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider,
            OrderCloseService orderCloseService,
            PaymentAttemptService paymentAttemptService,
            PaymentFinalizationService paymentFinalizationService,
            PaymentTimeoutScanProperties properties,
            PaymentInitiationProperties initiationProperties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
        this.orderCloseService = orderCloseService;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentFinalizationService = paymentFinalizationService;
        this.properties = properties;
        this.initiationProperties = initiationProperties;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /**
     * Retains the original callable entry point while applying the configured production batch bound.
     */
    public int closeExpiredPayments() {
        return closeExpiredPayments(properties.timeoutScanBatchSize());
    }

    public int closeExpiredPayments(int batchSize) {
        requireValidBatchSize(batchSize);
        Integer closed = withoutTransaction.execute(status -> closeExpiredPaymentsOutsideTransaction(batchSize));
        return closed == null ? 0 : closed;
    }

    private int closeExpiredPaymentsOutsideTransaction(int batchSize) {
        LocalDateTime scanTime = LocalDateTime.now(clock);
        if (!hasClaimableExpiredPayment(scanTime)) {
            return 0;
        }

        int closedCount = 0;
        for (int index = 0; index < batchSize; index++) {
            ClaimedPayment payment = claimNextExpiredPayment(scanTime);
            if (payment == null) {
                break;
            }
            try {
                ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                        payment.paymentConfigId(), payment.paymentConfigFingerprint());
                // Both provider operations happen after the claim transaction has committed.
                WechatPayOrderQueryResult queryResult = wechatPayProvider.queryOrder(
                        config, payment.outTradeNo());
                validateProviderIdentity(payment, queryResult);
                if (queryResult.paid()) {
                    paymentFinalizationService.finalizePaid(
                            payment.outTradeNo(),
                            queryResult.transactionId(),
                            queryResult.amountCent(),
                            queryResult.paidAt() == null ? LocalDateTime.now(clock) : queryResult.paidAt(),
                            "",
                            config
                    );
                    continue;
                }
                if (!isAlreadyClosedAtProvider(queryResult.tradeState())) {
                    wechatPayProvider.closeOrder(config, payment.outTradeNo());
                }
                if (finalizeClaimedPayment(payment)) {
                    closedCount++;
                }
            } catch (RuntimeException ex) {
                recordClaimFailure(payment, ex);
                log.warn("One expired payment could not be closed; its lease will be retried (type={})",
                        safeErrorCode(ex));
            }
        }
        return closedCount;
    }

    private boolean hasClaimableExpiredPayment(LocalDateTime scanTime) {
        LocalDateTime expiredClaimBefore = scanTime.minus(properties.timeoutScanClaimTimeout());
        LocalDateTime expiredPrepayClaimBefore = scanTime.minus(initiationProperties.claimTimeout());
        return jdbcClient.sql("""
                        select id
                        from payment_order
                        where status in ('PREPARING', 'PAYING')
                          and expires_at <= :scanTime
                          and (
                              timeout_close_claim_token is null
                              or timeout_close_claimed_at is null
                              or timeout_close_claimed_at <= :expiredClaimBefore
                          )
                          and (
                              status = 'PAYING'
                              or prepay_claim_token is null
                              or prepay_claimed_at is null
                              or prepay_claimed_at <= :expiredPrepayClaimBefore
                          )
                        order by expires_at asc, id asc
                        limit 1
                        """)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .param("expiredPrepayClaimBefore", expiredPrepayClaimBefore)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    private ClaimedPayment claimNextExpiredPayment(LocalDateTime scanTime) {
        for (int attempt = 0; attempt < MAX_CLAIM_CONTENTION_RETRIES; attempt++) {
            ClaimedPayment claimedPayment = requiresNewTransaction.execute(
                    status -> claimNextExpiredPaymentOnce(scanTime));
            if (claimedPayment != null) {
                return claimedPayment;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private ClaimedPayment claimNextExpiredPaymentOnce(LocalDateTime scanTime) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        LocalDateTime expiredClaimBefore = claimedAt.minus(properties.timeoutScanClaimTimeout());
        LocalDateTime expiredPrepayClaimBefore = claimedAt.minus(initiationProperties.claimTimeout());
        List<ExpiredPaymentRow> candidates = jdbcClient.sql("""
                            select id as payment_order_id,
                                   order_id,
                                   payment_config_id,
                                   payment_config_fingerprint,
                                   out_trade_no
                            from payment_order
                            where status in ('PREPARING', 'PAYING')
                              and expires_at <= :scanTime
                              and (
                                  timeout_close_claim_token is null
                                  or timeout_close_claimed_at is null
                                  or timeout_close_claimed_at <= :expiredClaimBefore
                              )
                              and (
                                  status = 'PAYING'
                                  or prepay_claim_token is null
                                  or prepay_claimed_at is null
                                  or prepay_claimed_at <= :expiredPrepayClaimBefore
                              )
                            order by expires_at asc, id asc
                            limit 1
                            """)
                    .param("scanTime", scanTime)
                    .param("expiredClaimBefore", expiredClaimBefore)
                    .param("expiredPrepayClaimBefore", expiredPrepayClaimBefore)
                    .query(this::mapExpiredPaymentRow)
                    .list();
        if (candidates.isEmpty()) {
            return null;
        }

        ExpiredPaymentRow candidate = candidates.getFirst();
        String claimToken = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                            update payment_order
                            set timeout_close_claim_token = :claimToken,
                                timeout_close_claimed_at = :claimedAt,
                                timeout_close_attempts = timeout_close_attempts + 1,
                                updated_at = :claimedAt
                            where id = :paymentOrderId
                              and status in ('PREPARING', 'PAYING')
                              and expires_at <= :scanTime
                              and (
                                  timeout_close_claim_token is null
                                  or timeout_close_claimed_at is null
                                  or timeout_close_claimed_at <= :expiredClaimBefore
                              )
                              and (
                                  status = 'PAYING'
                                  or prepay_claim_token is null
                                  or prepay_claimed_at is null
                                  or prepay_claimed_at <= :expiredPrepayClaimBefore
                              )
                            """)
                    .param("claimToken", claimToken)
                    .param("claimedAt", claimedAt)
                    .param("scanTime", scanTime)
                    .param("expiredClaimBefore", expiredClaimBefore)
                    .param("expiredPrepayClaimBefore", expiredPrepayClaimBefore)
                    .param("paymentOrderId", candidate.paymentOrderId())
                    .update();
        if (updated != 1) {
            return null;
        }
        return new ClaimedPayment(
                candidate.paymentOrderId(), candidate.orderId(), candidate.paymentConfigId(),
                candidate.paymentConfigFingerprint(), candidate.outTradeNo(), claimToken);
    }

    private boolean finalizeClaimedPayment(ClaimedPayment payment) {
        Boolean closed = requiresNewTransaction.execute(status -> {
            LocalDateTime closedAt = LocalDateTime.now(clock);
            String orderStatus = lockOrderStatus(payment.orderId());
            PaymentClaimState claimState = jdbcClient.sql("""
                            select status,
                                   expires_at,
                                   timeout_close_claim_token
                            from payment_order
                            where id = :paymentOrderId
                            for update
                            """)
                    .param("paymentOrderId", payment.paymentOrderId())
                    .query(this::mapPaymentClaimState)
                    .optional()
                    .orElse(null);
            if (!OrderStatus.PAYING.name().equals(orderStatus)
                    || claimState == null
                    || !("PREPARING".equals(claimState.status()) || "PAYING".equals(claimState.status()))
                    || !payment.claimToken().equals(claimState.claimToken())
                    || claimState.expiresAt().isAfter(closedAt)) {
                clearClaimIfOwned(payment, closedAt);
                return false;
            }

            int updatedRows = jdbcClient.sql("""
                            update payment_order
                            set status = 'CLOSED',
                                closed_at = :closedAt,
                                timeout_close_claim_token = null,
                                timeout_close_claimed_at = null,
                                prepay_claim_token = null,
                                prepay_claimed_at = null,
                                last_error_code = '',
                                last_error_message = '',
                                updated_at = :closedAt
                            where id = :paymentOrderId
                              and status = :currentStatus
                              and timeout_close_claim_token = :claimToken
                            """)
                    .param("closedAt", closedAt)
                    .param("paymentOrderId", payment.paymentOrderId())
                    .param("currentStatus", claimState.status())
                    .param("claimToken", payment.claimToken())
                    .update();
            if (updatedRows != 1) {
                return false;
            }
            paymentAttemptService.closed(payment.paymentOrderId(), closedAt);
            orderCloseService.closePayingOrder(
                    payment.orderId(), "PAY_TIMEOUT", OPERATOR_TYPE_SYSTEM, 0L);
            return true;
        });
        return Boolean.TRUE.equals(closed);
    }

    private String lockOrderStatus(Long orderId) {
        return jdbcClient.sql("""
                        select status
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void clearClaimIfOwned(ClaimedPayment payment, LocalDateTime updatedAt) {
        jdbcClient.sql("""
                        update payment_order
                        set timeout_close_claim_token = null,
                            timeout_close_claimed_at = null,
                            updated_at = :updatedAt
                        where id = :paymentOrderId
                          and timeout_close_claim_token = :claimToken
                        """)
                .param("updatedAt", updatedAt)
                .param("paymentOrderId", payment.paymentOrderId())
                .param("claimToken", payment.claimToken())
                .update();
    }

    private void recordClaimFailure(ClaimedPayment payment, RuntimeException failure) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> jdbcClient.sql("""
                            update payment_order
                            set last_error_code = :errorCode,
                                last_error_message = 'Timeout close failed; retry scheduled',
                                updated_at = :updatedAt
                            where id = :paymentOrderId
                              and status in ('PREPARING', 'PAYING')
                              and timeout_close_claim_token = :claimToken
                            """)
                    .param("errorCode", safeErrorCode(failure))
                    .param("updatedAt", LocalDateTime.now(clock))
                    .param("paymentOrderId", payment.paymentOrderId())
                    .param("claimToken", payment.claimToken())
                    .update());
        } catch (RuntimeException persistenceFailure) {
            log.warn("An expired payment failure could not be recorded (type={})",
                    safeErrorCode(persistenceFailure));
        }
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
            throw new IllegalArgumentException("Payment timeout batch size must be between 1 and 500");
        }
    }

    private ExpiredPaymentRow mapExpiredPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExpiredPaymentRow(
                rs.getLong("payment_order_id"),
                rs.getLong("order_id"),
                nullableLong(rs, "payment_config_id"),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no")
        );
    }

    private PaymentClaimState mapPaymentClaimState(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentClaimState(
                rs.getString("status"),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getString("timeout_close_claim_token")
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private record ExpiredPaymentRow(
            Long paymentOrderId,
            Long orderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo
    ) {
    }

    private record ClaimedPayment(
            Long paymentOrderId,
            Long orderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String claimToken
    ) {
    }

    private record PaymentClaimState(String status, LocalDateTime expiresAt, String claimToken) {
    }

    private void validateProviderIdentity(
            ClaimedPayment payment,
            WechatPayOrderQueryResult queryResult
    ) {
        if (queryResult == null || !payment.outTradeNo().equals(queryResult.outTradeNo())) {
            throw new IllegalStateException("Payment provider returned a mismatched merchant order number");
        }
    }

    private boolean isAlreadyClosedAtProvider(String tradeState) {
        return "CLOSED".equals(tradeState)
                || "REVOKED".equals(tradeState)
                || "NOT_FOUND".equals(tradeState);
    }
}
