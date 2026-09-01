package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.payment.PaymentTimeoutZSetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shop.pay.timeout-zset",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PaymentTimeoutZSetScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutZSetScheduler.class);

    private final PaymentTimeoutZSetWorker worker;
    private final PaymentTimeoutZSetProperties properties;

    public PaymentTimeoutZSetScheduler(
            PaymentTimeoutZSetWorker worker,
            PaymentTimeoutZSetProperties properties
    ) {
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${shop.pay.timeout-zset.poll-delay:1s}",
            initialDelayString = "${shop.pay.timeout-zset.initial-delay:5s}"
    )
    public void runOnce() {
        try {
            worker.runOnce(properties.batchSize());
        } catch (RuntimeException ex) {
            log.warn("Payment timeout ZSet polling failed; the database scan remains available (type={})",
                    safeErrorCode(ex));
        }
    }

    private String safeErrorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "RuntimeException" : name;
    }
}
