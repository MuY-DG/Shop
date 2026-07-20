package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.muybaby.shopserver.payment.config.PaymentConfigIdentityValidator;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.realtime.OrderPaidRealtimeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Set;

@Service
public class PaymentFinalizationService {

    private static final Set<String> PAID_DUPLICATE_ORDER_STATUSES = Set.of(
            OrderStatus.PAID.name(),
            OrderStatus.SHIPPED.name(),
            OrderStatus.COMPLETED.name(),
            OrderStatus.REFUNDING.name(),
            OrderStatus.REFUNDED.name()
    );

    private final JdbcClient jdbcClient;
    private final OrderStatusLogService orderStatusLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentConfigIdentityValidator paymentConfigIdentityValidator;

    public PaymentFinalizationService(
            JdbcClient jdbcClient,
            OrderStatusLogService orderStatusLogService,
            ApplicationEventPublisher eventPublisher,
            PaymentAttemptService paymentAttemptService,
            PaymentConfigIdentityValidator paymentConfigIdentityValidator
    ) {
        this.jdbcClient = jdbcClient;
        this.orderStatusLogService = orderStatusLogService;
        this.eventPublisher = eventPublisher;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentConfigIdentityValidator = paymentConfigIdentityValidator;
    }

    @Transactional
    public PaidFinalizationResult finalizePaid(
            String outTradeNo,
            String transactionId,
            long amountCent,
            LocalDateTime paidAt,
            String callbackDigest,
            ResolvedPaymentConfig verifiedConfig
    ) {
        PaymentRoute route = findPaymentRoute(outTradeNo);
        OrderPaymentRow order = findOrderForUpdate(route.orderId());
        PaymentOrderRow payment = findPaymentForUpdate(outTradeNo);
        if (!route.paymentOrderId().equals(payment.paymentOrderId())
                || !route.orderId().equals(payment.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        paymentConfigIdentityValidator.validate(
                payment.paymentConfigId(), payment.paymentConfigFingerprint(), verifiedConfig);
        LocalDateTime effectivePaidAt = paidAt == null ? LocalDateTime.now() : paidAt;
        if (OrderStatus.PAID.name().equals(payment.status())) {
            validatePaidDuplicate(payment, transactionId, amountCent);
            validatePaidDuplicateOrder(order, outTradeNo, transactionId, amountCent);
            paymentAttemptService.paid(payment.paymentOrderId(), effectivePaidAt);
            return new PaidFinalizationResult(order.orderId(), order.status(), payment.transactionId(), true);
        }
        if (!("PREPARING".equals(payment.status()) || OrderStatus.PAYING.name().equals(payment.status()))
                || payment.amountCent() != amountCent) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (!OrderStatus.PAYING.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        int paymentRows = jdbcClient.sql("""
                        update payment_order
                        set status = 'PAID',
                            transaction_id = :transactionId,
                            callback_digest = :callbackDigest,
                            timeout_close_claim_token = null,
                            timeout_close_claimed_at = null,
                            prepay_claim_token = null,
                            prepay_claimed_at = null,
                            paid_at = :paidAt,
                            updated_at = :updatedAt
                        where out_trade_no = :outTradeNo
                          and status in ('PREPARING', 'PAYING')
                        """)
                .param("transactionId", transactionId)
                .param("callbackDigest", nullToEmpty(callbackDigest))
                .param("paidAt", effectivePaidAt)
                .param("updatedAt", LocalDateTime.now())
                .param("outTradeNo", outTradeNo)
                .update();
        requireUpdated(paymentRows, "payment paid state");

        int orderRows = jdbcClient.sql("""
                        update shop_order
                        set status = 'PAID',
                            paid_amount_cent = :paidAmountCent,
                            paid_at = :paidAt,
                            payment_transaction_id = :transactionId,
                            merchant_trade_no = :outTradeNo,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = 'PAYING'
                        """)
                .param("paidAmountCent", amountCent)
                .param("paidAt", effectivePaidAt)
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .param("updatedAt", LocalDateTime.now())
                .param("orderId", order.orderId())
                .update();
        requireUpdated(orderRows, "order paid state");

        int expectedStockLocks = jdbcClient.sql("select count(*) from order_item where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single();
        int totalStockLocks = jdbcClient.sql("select count(*) from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single();
        int validStockLockMappings = jdbcClient.sql("""
                        select count(*)
                        from (
                            select oi.id
                            from order_item oi
                            left join stock_lock sl
                              on sl.order_id = oi.order_id
                             and sl.order_item_id = oi.id
                             and sl.sku_id = oi.sku_id
                             and sl.quantity = oi.quantity
                             and sl.status = :locked
                            where oi.order_id = :orderId
                            group by oi.id
                            having count(sl.id) = 1
                        ) valid_lock
                        """)
                .param("locked", StockLockStatus.LOCKED.name())
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single();
        if (totalStockLocks != expectedStockLocks || validStockLockMappings != expectedStockLocks) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        int confirmedStockLocks = jdbcClient.sql("""
                        update stock_lock
                        set status = :confirmed,
                            updated_at = :updatedAt
                        where order_id = :orderId
                          and status = :locked
                        """)
                .param("confirmed", StockLockStatus.CONFIRMED.name())
                .param("updatedAt", LocalDateTime.now())
                .param("orderId", order.orderId())
                .param("locked", StockLockStatus.LOCKED.name())
                .update();
        if (confirmedStockLocks != expectedStockLocks) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (order.userCouponId() != null) {
            int couponRows = jdbcClient.sql("""
                            update user_coupon
                            set status = :used,
                                used_order_id = :orderId,
                                used_at = :usedAt,
                                updated_at = :updatedAt
                            where id = :userCouponId
                              and locked_order_id = :orderId
                              and status = :locked
                            """)
                    .param("used", UserCouponStatus.USED.name())
                    .param("orderId", order.orderId())
                    .param("usedAt", effectivePaidAt)
                    .param("updatedAt", LocalDateTime.now())
                    .param("userCouponId", order.userCouponId())
                    .param("locked", UserCouponStatus.LOCKED.name())
                    .update();
            requireUpdated(couponRows, "coupon used state");
        }
        orderStatusLogService.record(
                order.orderId(), OrderStatus.PAYING.name(), OrderStatus.PAID.name(),
                "PAYMENT_SUCCEEDED", "WECHAT", null, "微信支付成功", effectivePaidAt
        );
        eventPublisher.publishEvent(new OrderPaidRealtimeEvent(
                order.orderId(), order.orderNo(), amountCent, effectivePaidAt
        ));
        paymentAttemptService.paid(payment.paymentOrderId(), effectivePaidAt);
        return new PaidFinalizationResult(order.orderId(), OrderStatus.PAID.name(), transactionId, false);
    }

    private PaymentRoute findPaymentRoute(String outTradeNo) {
        return jdbcClient.sql("""
                        select id, order_id
                        from payment_order
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
                .query((rs, rowNum) -> new PaymentRoute(rs.getLong("id"), rs.getLong("order_id")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private PaymentOrderRow findPaymentForUpdate(String outTradeNo) {
        return jdbcClient.sql("""
                        select id, order_id, payment_config_id, payment_config_fingerprint,
                               out_trade_no, transaction_id, status, amount_cent
                        from payment_order
                        where out_trade_no = :outTradeNo
                        for update
                        """)
                .param("outTradeNo", outTradeNo)
                .query(this::mapPaymentOrderRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private OrderPaymentRow findOrderForUpdate(Long orderId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               status,
                               paid_amount_cent,
                               user_coupon_id,
                               payment_transaction_id,
                               merchant_trade_no
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderPaymentRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private void validatePaidDuplicate(PaymentOrderRow payment, String transactionId, long amountCent) {
        if (payment.amountCent() != amountCent
                || !StringUtils.hasText(payment.transactionId())
                || !payment.transactionId().equals(transactionId)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void validatePaidDuplicateOrder(
            OrderPaymentRow order,
            String outTradeNo,
            String transactionId,
            long amountCent
    ) {
        if (!PAID_DUPLICATE_ORDER_STATUSES.contains(order.status())
                || order.paidAmountCent() != amountCent
                || !outTradeNo.equals(order.merchantTradeNo())
                || !StringUtils.hasText(order.paymentTransactionId())
                || !order.paymentTransactionId().equals(transactionId)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireUpdated(int updatedRows, String transition) {
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private PaymentOrderRow mapPaymentOrderRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentOrderRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getObject("payment_config_id", Long.class),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no"),
                rs.getString("transaction_id"),
                rs.getString("status"),
                rs.getLong("amount_cent")
        );
    }

    private OrderPaymentRow mapOrderPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderPaymentRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("paid_amount_cent"),
                rs.getObject("user_coupon_id", Long.class),
                rs.getString("payment_transaction_id"),
                rs.getString("merchant_trade_no")
        );
    }

    private record PaymentOrderRow(
            Long paymentOrderId,
            Long orderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String transactionId,
            String status,
            long amountCent
    ) {
    }

    private record PaymentRoute(Long paymentOrderId, Long orderId) {
    }

    private record OrderPaymentRow(
            Long orderId,
            String orderNo,
            String status,
            long paidAmountCent,
            Long userCouponId,
            String paymentTransactionId,
            String merchantTradeNo
    ) {
    }

    public record PaidFinalizationResult(
            Long orderId,
            String orderStatus,
            String transactionId,
            boolean duplicate
    ) {
    }
}
