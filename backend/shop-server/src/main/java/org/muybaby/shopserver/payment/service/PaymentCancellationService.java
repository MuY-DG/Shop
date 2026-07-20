package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderCloseService;
import org.muybaby.shopserver.payment.PaymentInitiationProperties;
import org.muybaby.shopserver.payment.PaymentTimeoutScanProperties;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.PaymentCancelResponse;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentCancellationService {

    private static final String OPERATOR_TYPE_APP = "APP";
    private static final String CLOSE_REASON = "APP_CANCEL";

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;
    private final OrderCloseService orderCloseService;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentFinalizationService paymentFinalizationService;
    private final PaymentTimeoutScanProperties timeoutProperties;
    private final PaymentInitiationProperties initiationProperties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public PaymentCancellationService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider,
            OrderCloseService orderCloseService,
            PaymentAttemptService paymentAttemptService,
            PaymentFinalizationService paymentFinalizationService,
            PaymentTimeoutScanProperties timeoutProperties,
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
        this.timeoutProperties = timeoutProperties;
        this.initiationProperties = initiationProperties;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public PaymentCancelResponse cancel(Long userId, Long orderId) {
        PaymentCancelResponse response = withoutTransaction.execute(status -> cancelOutsideTransaction(userId, orderId));
        if (response == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return response;
    }

    private PaymentCancelResponse cancelOutsideTransaction(Long userId, Long orderId) {
        CancellationPreparation preparation = requiresNewTransaction.execute(
                status -> prepareCancellation(userId, orderId));
        if (preparation == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (preparation.providerClose() == null) {
            return new PaymentCancelResponse(orderId, OrderStatus.CLOSED.name());
        }

        ClaimedPayment payment = preparation.providerClose();
        try {
            ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                    payment.paymentConfigId(), payment.paymentConfigFingerprint());
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
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            if (!isAlreadyClosedAtProvider(queryResult.tradeState())) {
                wechatPayProvider.closeOrder(config, payment.outTradeNo());
            }
        } catch (RuntimeException ex) {
            releaseFailedClaim(payment, ex);
            throw ex;
        }
        Boolean closed = requiresNewTransaction.execute(status -> finalizeCancellation(payment, userId));
        if (!Boolean.TRUE.equals(closed)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return new PaymentCancelResponse(orderId, OrderStatus.CLOSED.name());
    }

    private CancellationPreparation prepareCancellation(Long userId, Long orderId) {
        OrderState observedOrder = jdbcClient.sql("""
                        select id, status
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrderState)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (OrderStatus.CREATED.name().equals(observedOrder.status())) {
            OrderState lockedOrder = lockOwnedOrder(userId, orderId);
            if (!OrderStatus.CREATED.name().equals(lockedOrder.status())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            orderCloseService.closeCreatedOrder(lockedOrder.id(), CLOSE_REASON, OPERATOR_TYPE_APP, userId);
            return new CancellationPreparation(null);
        }
        if (!OrderStatus.PAYING.name().equals(observedOrder.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        OrderState lockedOrder = lockOwnedOrder(userId, orderId);
        if (!OrderStatus.PAYING.name().equals(lockedOrder.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        PaymentClaimState payment = jdbcClient.sql("""
                        select id, payment_config_id, payment_config_fingerprint, out_trade_no, status,
                               timeout_close_claim_token, timeout_close_claimed_at,
                               prepay_claim_token, prepay_claimed_at
                        from payment_order
                        where order_id = :orderId
                          and status in ('PREPARING', 'PAYING')
                        order by id desc
                        limit 1
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapPaymentClaimState)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        LocalDateTime expiredClaimBefore = claimedAt.minus(timeoutProperties.timeoutScanClaimTimeout());
        LocalDateTime expiredPrepayClaimBefore = claimedAt.minus(initiationProperties.claimTimeout());
        if (payment.claimToken() != null
                && payment.claimedAt() != null
                && payment.claimedAt().isAfter(expiredClaimBefore)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if ("PREPARING".equals(payment.status())
                && payment.prepayClaimToken() != null
                && payment.prepayClaimedAt() != null
                && payment.prepayClaimedAt().isAfter(expiredPrepayClaimBefore)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        String claimToken = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update payment_order
                        set timeout_close_claim_token = :claimToken,
                            timeout_close_claimed_at = :claimedAt,
                            timeout_close_attempts = timeout_close_attempts + 1,
                            updated_at = :updatedAt
                        where id = :paymentOrderId
                          and status = :paymentStatus
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
                .param("updatedAt", claimedAt)
                .param("paymentOrderId", payment.paymentOrderId())
                .param("paymentStatus", payment.status())
                .param("expiredClaimBefore", expiredClaimBefore)
                .param("expiredPrepayClaimBefore", expiredPrepayClaimBefore)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return new CancellationPreparation(new ClaimedPayment(
                payment.paymentOrderId(), orderId, payment.paymentConfigId(),
                payment.paymentConfigFingerprint(), payment.outTradeNo(), claimToken));
    }

    private OrderState lockOwnedOrder(Long userId, Long orderId) {
        return jdbcClient.sql("""
                        select id, status
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrderState)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private boolean finalizeCancellation(ClaimedPayment payment, Long userId) {
        LocalDateTime closedAt = LocalDateTime.now(clock);
        OrderState lockedOrder = lockOwnedOrder(userId, payment.orderId());
        PaymentClaimState current = jdbcClient.sql("""
                        select id, payment_config_id, payment_config_fingerprint, out_trade_no, status,
                               timeout_close_claim_token, timeout_close_claimed_at,
                               prepay_claim_token, prepay_claimed_at
                        from payment_order
                        where id = :paymentOrderId
                        for update
                        """)
                .param("paymentOrderId", payment.paymentOrderId())
                .query(this::mapPaymentClaimState)
                .optional()
                .orElse(null);
        if (!OrderStatus.PAYING.name().equals(lockedOrder.status())
                || current == null
                || !("PREPARING".equals(current.status()) || "PAYING".equals(current.status()))
                || !payment.claimToken().equals(current.claimToken())) {
            clearClaimIfOwned(payment, closedAt);
            return false;
        }

        int updated = jdbcClient.sql("""
                        update payment_order
                        set status = 'CLOSED',
                            closed_at = :closedAt,
                            timeout_close_claim_token = null,
                            timeout_close_claimed_at = null,
                            prepay_claim_token = null,
                            prepay_claimed_at = null,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :updatedAt
                        where id = :paymentOrderId
                          and status = :currentStatus
                          and timeout_close_claim_token = :claimToken
                        """)
                .param("closedAt", closedAt)
                .param("updatedAt", closedAt)
                .param("paymentOrderId", payment.paymentOrderId())
                .param("currentStatus", current.status())
                .param("claimToken", payment.claimToken())
                .update();
        if (updated != 1) {
            return false;
        }
        paymentAttemptService.closed(payment.paymentOrderId(), closedAt);
        orderCloseService.closePayingOrder(payment.orderId(), CLOSE_REASON, OPERATOR_TYPE_APP, userId);
        return true;
    }

    private void releaseFailedClaim(ClaimedPayment payment, RuntimeException failure) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> jdbcClient.sql("""
                            update payment_order
                            set timeout_close_claim_token = null,
                                timeout_close_claimed_at = null,
                                last_error_code = :errorCode,
                                last_error_message = 'App payment cancellation failed; retry allowed',
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
        } catch (RuntimeException ignored) {
            // The lease expires and remains recoverable even if recording the failure also fails.
        }
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

    private String safeErrorCode(RuntimeException failure) {
        String simpleName = failure.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return "RuntimeException";
        }
        return simpleName.length() <= 64 ? simpleName : simpleName.substring(0, 64);
    }

    private OrderState mapOrderState(ResultSet rs, int rowNum) throws SQLException {
        return new OrderState(rs.getLong("id"), rs.getString("status"));
    }

    private PaymentClaimState mapPaymentClaimState(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentClaimState(
                rs.getLong("id"),
                nullableLong(rs, "payment_config_id"),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no"),
                rs.getString("status"),
                rs.getString("timeout_close_claim_token"),
                rs.getObject("timeout_close_claimed_at", LocalDateTime.class),
                rs.getString("prepay_claim_token"),
                rs.getObject("prepay_claimed_at", LocalDateTime.class)
        );
    }

    private record OrderState(Long id, String status) {
    }

    private record PaymentClaimState(
            Long paymentOrderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String status,
            String claimToken,
            LocalDateTime claimedAt,
            String prepayClaimToken,
            LocalDateTime prepayClaimedAt
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

    private record CancellationPreparation(ClaimedPayment providerClose) {
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private void validateProviderIdentity(ClaimedPayment payment, WechatPayOrderQueryResult queryResult) {
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
