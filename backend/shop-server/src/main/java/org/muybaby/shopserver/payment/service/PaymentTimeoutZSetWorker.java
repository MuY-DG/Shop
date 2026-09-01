package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.payment.PaymentTimeoutZSetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class PaymentTimeoutZSetWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutZSetWorker.class);

    private final PaymentTimeoutZSetQueue queue;
    private final PaymentTimeoutZSetProcessor processor;
    private final PaymentTimeoutZSetProperties properties;
    private final Clock clock;

    public PaymentTimeoutZSetWorker(
            PaymentTimeoutZSetQueue queue,
            PaymentTimeoutZSetProcessor processor,
            PaymentTimeoutZSetProperties properties,
            Clock clock
    ) {
        this.queue = queue;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    public void runOnce(int batchSize) {
        Instant polledAt = clock.instant();
        List<Long> dueOrderIds = queue.due(polledAt, batchSize);
        for (Long orderId : dueOrderIds) {
            try {
                PaymentTimeoutZSetProcessor.Result result = processor.process(orderId);
                if (result.acknowledged()) {
                    queue.acknowledge(orderId);
                } else {
                    queue.reschedule(orderId, result.nextAttemptAt());
                }
            } catch (RuntimeException ex) {
                rescheduleAfterFailure(orderId, ex);
            }
        }
    }

    private void rescheduleAfterFailure(Long orderId, RuntimeException failure) {
        try {
            queue.reschedule(orderId, clock.instant().plus(properties.retryDelay()));
        } catch (RuntimeException redisFailure) {
            log.warn("A failed payment timeout ZSet item could not be rescheduled; the database scan will recover it (type={})",
                    safeErrorCode(redisFailure));
        }
        log.warn("One payment timeout ZSet item failed and will be retried (type={})", safeErrorCode(failure));
    }

    private String safeErrorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "RuntimeException" : name;
    }
}
