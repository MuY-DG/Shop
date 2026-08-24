package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.PaymentCancelResponse;
import org.muybaby.shopserver.payment.dto.PaymentSyncResponse;
import org.muybaby.shopserver.payment.dto.WechatPaymentParamsResponse;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AppPaymentService {

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;
    private final PaymentInitiationService paymentInitiationService;
    private final PaymentFinalizationService paymentFinalizationService;
    private final PaymentCancellationService paymentCancellationService;

    public AppPaymentService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider,
            PaymentInitiationService paymentInitiationService,
            PaymentFinalizationService paymentFinalizationService,
            PaymentCancellationService paymentCancellationService
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
        this.paymentInitiationService = paymentInitiationService;
        this.paymentFinalizationService = paymentFinalizationService;
        this.paymentCancellationService = paymentCancellationService;
    }

    public WechatPaymentParamsResponse pay(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        return paymentInitiationService.initiate(userId, orderId);
    }

    public PaymentSyncResponse sync(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        OrderPaymentRow order = findOrderSnapshot(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (OrderStatus.PAID.name().equals(order.status())) {
            return new PaymentSyncResponse(order.orderId(), order.status(), order.paymentTransactionId());
        }
        if (!OrderStatus.PAYING.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        PaymentOrderRow payment = findReconcilablePaymentSnapshot(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                payment.paymentConfigId(), payment.paymentConfigFingerprint());
        WechatPayOrderQueryResult queryResult = wechatPayProvider.queryOrder(config, payment.outTradeNo());
        if (queryResult == null || !payment.outTradeNo().equals(queryResult.outTradeNo())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (!queryResult.paid()) {
            return new PaymentSyncResponse(order.orderId(), order.status(), "");
        }
        PaymentFinalizationService.PaidFinalizationResult result = paymentFinalizationService.finalizePaid(
                queryResult.outTradeNo(),
                queryResult.transactionId(),
                queryResult.amountCent(),
                queryResult.paidAt() == null ? LocalDateTime.now(java.time.ZoneOffset.UTC) : queryResult.paidAt(),
                "",
                config
        );
        if (!order.orderId().equals(result.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return new PaymentSyncResponse(result.orderId(), result.orderStatus(), result.transactionId());
    }

    public PaymentCancelResponse cancel(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        return paymentCancellationService.cancel(userId, orderId);
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private Optional<OrderPaymentRow> findOrderSnapshot(Long orderId, Long userId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               user_id,
                               status,
                               payable_amount_cent,
                               paid_amount_cent,
                               user_coupon_id,
                               payment_transaction_id,
                               merchant_trade_no
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrderPaymentRow)
                .optional();
    }

    private Optional<PaymentOrderRow> findReconcilablePaymentSnapshot(Long orderId) {
        return jdbcClient.sql("""
                        select id,
                               order_id,
                               payment_config_id,
                               payment_config_fingerprint,
                               out_trade_no,
                               prepay_id,
                               transaction_id,
                               status,
                               amount_cent,
                               expires_at
                        from payment_order
                        where order_id = :orderId
                          and status in ('PREPARING', 'PAYING')
                        order by id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(this::mapPaymentOrderRow)
                .optional();
    }

    private OrderPaymentRow mapOrderPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderPaymentRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getObject("user_coupon_id", Long.class),
                rs.getString("payment_transaction_id"),
                rs.getString("merchant_trade_no")
        );
    }

    private PaymentOrderRow mapPaymentOrderRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentOrderRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("payment_config_id"),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no"),
                rs.getString("prepay_id"),
                rs.getString("transaction_id"),
                rs.getString("status"),
                rs.getLong("amount_cent"),
                rs.getObject("expires_at", LocalDateTime.class)
        );
    }

    private record OrderPaymentRow(
            Long orderId,
            String orderNo,
            Long userId,
            String status,
            long payableAmountCent,
            long paidAmountCent,
            Long userCouponId,
            String paymentTransactionId,
            String merchantTradeNo
    ) {
    }

    private record PaymentOrderRow(
            Long paymentOrderId,
            Long orderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String prepayId,
            String transactionId,
            String status,
            long amountCent,
            LocalDateTime expiresAt
    ) {
    }

}
