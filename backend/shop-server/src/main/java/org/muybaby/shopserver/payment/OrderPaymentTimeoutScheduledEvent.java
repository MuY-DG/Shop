package org.muybaby.shopserver.payment;

import java.time.LocalDateTime;
import java.util.Objects;

public record OrderPaymentTimeoutScheduledEvent(Long orderId, LocalDateTime expiresAt) {

    public OrderPaymentTimeoutScheduledEvent {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Order id must be positive");
        }
        Objects.requireNonNull(expiresAt, "Payment expiry must not be null");
    }
}
