package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.logistics.WechatReceiptReconciliationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WechatReceiptReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            WechatReceiptReconciliationScheduler.class);

    private final WechatReceiptReconciliationService reconciliationService;
    private final WechatReceiptReconciliationProperties properties;
    private final WechatShippingRuntimeSettingService runtimeSettingService;

    public WechatReceiptReconciliationScheduler(
            WechatReceiptReconciliationService reconciliationService,
            WechatReceiptReconciliationProperties properties,
            WechatShippingRuntimeSettingService runtimeSettingService
    ) {
        this.reconciliationService = reconciliationService;
        this.properties = properties;
        this.runtimeSettingService = runtimeSettingService;
    }

    @Scheduled(
            fixedDelayString = "${shop.wechat.shipping.receipt-reconciliation.delay:5m}",
            initialDelayString = "${shop.wechat.shipping.receipt-reconciliation.initial-delay:${shop.wechat.shipping.receipt-reconciliation.delay:5m}}"
    )
    public void runOnce() {
        if (!runtimeSettingService.receiptReconciliationEnabledFailClosed()) {
            return;
        }
        try {
            reconciliationService.reconcilePendingReceipts(properties.batchSize());
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat receipt reconciliation scan failed; it will retry on the next tick (type={})",
                    ex.getClass().getSimpleName()
            );
        }
    }
}
