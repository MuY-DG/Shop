package org.muybaby.shopserver.order.service;

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
public class CreatedOrderTimeoutCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(CreatedOrderTimeoutCloseScheduler.class);

    private final CreatedOrderTimeoutCloseService service;
    private final PaymentTimeoutScanProperties properties;

    public CreatedOrderTimeoutCloseScheduler(
            CreatedOrderTimeoutCloseService service,
            PaymentTimeoutScanProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${shop.pay.timeout-scan-delay:5m}",
            initialDelayString = "${shop.pay.timeout-scan-initial-delay:${shop.pay.timeout-scan-delay:5m}}"
    )
    public void runOnce() {
        try {
            service.closeExpiredCreatedOrders(properties.timeoutScanBatchSize());
        } catch (RuntimeException ex) {
            log.warn("Created order timeout scan failed; it will be retried on the next tick (type={})",
                    ex.getClass().getSimpleName());
        }
    }
}
