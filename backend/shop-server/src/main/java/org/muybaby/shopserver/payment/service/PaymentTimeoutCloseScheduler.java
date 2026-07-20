package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.payment.PaymentTimeoutScanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shop.pay",
        name = "timeout-scan-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PaymentTimeoutCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutCloseScheduler.class);

    private final PaymentTimeoutCloseService paymentTimeoutCloseService;
    private final PaymentTimeoutScanProperties properties;

    public PaymentTimeoutCloseScheduler(
            PaymentTimeoutCloseService paymentTimeoutCloseService,
            PaymentTimeoutScanProperties properties
    ) {
        this.paymentTimeoutCloseService = paymentTimeoutCloseService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${shop.pay.timeout-scan-delay:60s}",
            initialDelayString = "${shop.pay.timeout-scan-initial-delay:${shop.pay.timeout-scan-delay:60s}}"
    )
    public void runOnce() {
        try {
            paymentTimeoutCloseService.closeExpiredPayments(properties.timeoutScanBatchSize());
        } catch (RuntimeException ex) {
            log.warn("Payment timeout scan failed; it will be retried on the next tick (type={})",
                    ex.getClass().getSimpleName());
        }
    }
}
