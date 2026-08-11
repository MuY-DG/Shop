package org.muybaby.shopserver.wechat.servicecard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class WechatServiceCardDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WechatServiceCardDeliveryScheduler.class);

    private final WechatServiceCardRuntimeSettingService runtimeSettingService;
    private final WechatServiceCardDeliveryStore store;
    private final WechatServiceCardDeliveryCoordinator coordinator;
    private final Clock clock;

    public WechatServiceCardDeliveryScheduler(
            WechatServiceCardRuntimeSettingService runtimeSettingService,
            WechatServiceCardDeliveryStore store,
            WechatServiceCardDeliveryCoordinator coordinator,
            Clock clock
    ) {
        this.runtimeSettingService = runtimeSettingService;
        this.store = store;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${shop.wechat.service-card-2001.delay:15s}",
            initialDelayString = "${shop.wechat.service-card-2001.initial-delay:${shop.wechat.service-card-2001.delay:15s}}"
    )
    public void runOnce() {
        if (!runtimeSettingService.workerReadyFailClosed()) {
            return;
        }
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
