package org.muybaby.shopserver.payment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shop.pay.secret-encryption",
        name = "rotation-enabled",
        havingValue = "true"
)
public class PaymentSecretRotationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentSecretRotationScheduler.class);

    private final PaymentSecretRotationService rotationService;

    public PaymentSecretRotationScheduler(PaymentSecretRotationService rotationService) {
        this.rotationService = rotationService;
    }

    @Scheduled(
            fixedDelayString = "${shop.pay.secret-encryption.rotation-delay:1m}",
            initialDelayString = "${shop.pay.secret-encryption.rotation-delay:1m}"
    )
    public void runOnce() {
        try {
            int rotated = rotationService.rotateBatch();
            if (rotated > 0) {
                log.info("Re-encrypted {} persisted secret envelopes with the active key", rotated);
            }
        } catch (RuntimeException ex) {
            log.warn("Secret rotation scan failed; it will be retried (type={})",
                    ex.getClass().getSimpleName());
        }
    }
}
