package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.RefundRecoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shop.pay.refund-recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RefundRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundRecoveryScheduler.class);

    private final RefundRecoveryService refundRecoveryService;
    private final RefundRecoveryProperties properties;

    public RefundRecoveryScheduler(
            RefundRecoveryService refundRecoveryService,
            RefundRecoveryProperties properties
    ) {
        this.refundRecoveryService = refundRecoveryService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${shop.pay.refund-recovery.delay:60s}",
            initialDelayString = "${shop.pay.refund-recovery.initial-delay:${shop.pay.refund-recovery.delay:60s}}"
    )
    public void runOnce() {
        try {
            refundRecoveryService.recoverPendingRefunds(properties.batchSize());
        } catch (RuntimeException ex) {
            log.warn("Refund recovery scan failed; it will be retried on the next tick (type={})",
                    ex.getClass().getSimpleName());
        }
    }
}
