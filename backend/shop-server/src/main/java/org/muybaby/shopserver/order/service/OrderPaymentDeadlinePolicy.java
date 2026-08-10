package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.payment.PaymentProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OrderPaymentDeadlinePolicy {

    private static final int DEFAULT_EXPIRE_MINUTES = 15;

    private final PaymentProperties paymentProperties;

    public OrderPaymentDeadlinePolicy(PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
    }

    public LocalDateTime deadlineFrom(LocalDateTime submittedAt) {
        if (submittedAt == null) {
            throw new IllegalArgumentException("Order submission time is required");
        }
        return submittedAt.plusMinutes(expireMinutes());
    }

    public int expireMinutes() {
        Integer configured = paymentProperties.expireMinutes();
        return configured == null || configured < 1 ? DEFAULT_EXPIRE_MINUTES : configured;
    }
}
