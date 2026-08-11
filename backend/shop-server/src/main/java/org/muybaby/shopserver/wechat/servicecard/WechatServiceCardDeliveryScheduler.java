package org.muybaby.shopserver.wechat.servicecard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(
        prefix = "shop.wechat.service-card-2001",
        name = "worker-enabled",
        havingValue = "true"
)
public class WechatServiceCardDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WechatServiceCardDeliveryScheduler.class);

    private final WechatServiceCardDeliveryStore store;
    private final WechatServiceCardDeliveryCoordinator coordinator;
    private final Clock clock;

    public WechatServiceCardDeliveryScheduler(
            WechatServiceCardDeliveryStore store,
            WechatServiceCardDeliveryCoordinator coordinator,
            Clock clock
    ) {
        this.store = store;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${shop.wechat.service-card-2001.delay:15s}",
            initialDelayString = "${shop.wechat.service-card-2001.initial-delay:${shop.wechat.service-card-2001.delay:15s}}"
    )
    public void runOnce() {
        try {
            store.reconcileStale(LocalDateTime.now(clock).withNano(0));
            coordinator.deliverDue();
            coordinator.reconcileDue();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat 2001 delivery scan failed; next scan will retry (type={})",
                    ex.getClass().getSimpleName()
            );
        }
    }
}
