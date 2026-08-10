package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.logistics.WechatShippingDeliveryProperties;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@ConditionalOnProperty(
        prefix = "shop.wechat.shipping.delivery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WechatShippingDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WechatShippingDeliveryScheduler.class);

    private final WechatShippingUploadStateStore stateStore;
    private final WechatShippingUploadCoordinator coordinator;
    private final WechatShippingDeliveryProperties properties;
    private final ShippingProperties shippingProperties;

    public WechatShippingDeliveryScheduler(
            WechatShippingUploadStateStore stateStore,
            WechatShippingUploadCoordinator coordinator,
            WechatShippingDeliveryProperties properties,
            ShippingProperties shippingProperties
    ) {
        this.stateStore = stateStore;
        this.coordinator = coordinator;
        this.properties = properties;
        this.shippingProperties = shippingProperties;
    }

    @Scheduled(
            fixedDelayString = "${shop.wechat.shipping.delivery.delay:15s}",
            initialDelayString = "${shop.wechat.shipping.delivery.initial-delay:${shop.wechat.shipping.delivery.delay:15s}}"
    )
    public void runOnce() {
        if (!shippingProperties.isUploadEnabled()) {
            return;
        }
        try {
            stateStore.reconcileStaleBatch(LocalDateTime.now(ZoneOffset.UTC));
            coordinator.deliverDue(properties.batchSize());
            coordinator.reconcileDueUnknown(properties.batchSize());
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping delivery scan failed; it will retry on the next tick (type={})",
                    ex.getClass().getSimpleName()
            );
        }
    }
}
