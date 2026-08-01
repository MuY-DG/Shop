package org.muybaby.shopserver.logistics.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class WechatShippingUploadRecovery {

    private static final Logger log = LoggerFactory.getLogger(WechatShippingUploadRecovery.class);

    private final WechatShippingUploadStateStore stateStore;

    public WechatShippingUploadRecovery(WechatShippingUploadStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleClaimsOnStartup() {
        try {
            int reconciled = stateStore.reconcileStaleBatch(LocalDateTime.now(java.time.ZoneOffset.UTC));
            if (reconciled > 0) {
                log.warn("Reconciled stale WeChat shipping attempts: count={}", reconciled);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Stale WeChat shipping recovery failed: exception={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    public boolean reconcileOrder(long orderId) {
        return stateStore.reconcileStaleByOrder(orderId, LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
}
