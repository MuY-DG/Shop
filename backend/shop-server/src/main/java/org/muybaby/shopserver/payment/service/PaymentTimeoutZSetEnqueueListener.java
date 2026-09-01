package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.payment.OrderPaymentTimeoutScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(
        prefix = "shop.pay.timeout-zset",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PaymentTimeoutZSetEnqueueListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutZSetEnqueueListener.class);

    private final PaymentTimeoutZSetQueue queue;

    public PaymentTimeoutZSetEnqueueListener(PaymentTimeoutZSetQueue queue) {
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderPaymentTimeoutScheduledEvent event) {
        try {
            queue.schedule(event.orderId(), event.expiresAt());
        } catch (RuntimeException ex) {
            log.warn("A committed order could not be added to the payment timeout ZSet; the database scan will recover it (type={})",
                    safeErrorCode(ex));
        }
    }

    private String safeErrorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "RuntimeException" : name;
    }
}
