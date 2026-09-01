package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.CreatedOrderTimeoutCloseService;
import org.muybaby.shopserver.payment.PaymentTimeoutZSetProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class PaymentTimeoutZSetProcessor {

    private final JdbcClient jdbcClient;
    private final CreatedOrderTimeoutCloseService createdOrderTimeoutCloseService;
    private final PaymentTimeoutCloseService paymentTimeoutCloseService;
    private final PaymentTimeoutZSetProperties properties;
    private final Clock clock;

    public PaymentTimeoutZSetProcessor(
            JdbcClient jdbcClient,
            CreatedOrderTimeoutCloseService createdOrderTimeoutCloseService,
            PaymentTimeoutCloseService paymentTimeoutCloseService,
            PaymentTimeoutZSetProperties properties,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.createdOrderTimeoutCloseService = createdOrderTimeoutCloseService;
        this.paymentTimeoutCloseService = paymentTimeoutCloseService;
        this.properties = properties;
        this.clock = clock;
    }

    public Result process(Long orderId) {
        requireOrderId(orderId);
        OrderTimeoutState before = findState(orderId);
        Result decision = decide(before);
        if (decision != null) {
            return decision;
        }

        if (OrderStatus.CREATED.name().equals(before.status())) {
            createdOrderTimeoutCloseService.closeExpiredCreatedOrder(orderId);
        } else {
            paymentTimeoutCloseService.closeExpiredPayment(orderId);
        }
        OrderTimeoutState after = findState(orderId);
        Result afterDecision = decide(after);
        return afterDecision == null
                ? Result.reschedule(clock.instant().plus(properties.retryDelay()))
                : afterDecision;
    }

    private Result decide(OrderTimeoutState state) {
        if (state == null || !isPaymentPending(state.status())) {
            return Result.acknowledge();
        }
        Instant expiresAt = state.paymentExpiresAt() == null
                ? null
                : state.paymentExpiresAt().toInstant(ZoneOffset.UTC);
        if (expiresAt == null) {
            return Result.reschedule(clock.instant().plus(properties.retryDelay()));
        }
        if (expiresAt.isAfter(clock.instant())) {
            return Result.reschedule(expiresAt);
        }
        return null;
    }

    private boolean isPaymentPending(String status) {
        return OrderStatus.CREATED.name().equals(status) || OrderStatus.PAYING.name().equals(status);
    }

    private OrderTimeoutState findState(Long orderId) {
        return jdbcClient.sql("""
                        select status, payment_expires_at
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", orderId)
                .query(this::mapState)
                .optional()
                .orElse(null);
    }

    private OrderTimeoutState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new OrderTimeoutState(
                rs.getString("status"),
                rs.getObject("payment_expires_at", LocalDateTime.class)
        );
    }

    private void requireOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Order id must be positive");
        }
    }

    private record OrderTimeoutState(String status, LocalDateTime paymentExpiresAt) {
    }

    public record Result(boolean acknowledged, Instant nextAttemptAt) {

        public Result {
            if (acknowledged == (nextAttemptAt != null)) {
                throw new IllegalArgumentException("A result must either acknowledge or provide one retry time");
            }
        }

        public static Result acknowledge() {
            return new Result(true, null);
        }

        public static Result reschedule(Instant nextAttemptAt) {
            if (nextAttemptAt == null) {
                throw new IllegalArgumentException("Next attempt time must not be null");
            }
            return new Result(false, nextAttemptAt);
        }
    }
}
