package org.muybaby.shopserver.finance.reconciliation.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradeReconciliationWorker {

    private final TradeReconciliationProcessor processor;
    private final FinanceReconciliationRuntimeSettingService runtimeSettingService;

    public TradeReconciliationWorker(
            TradeReconciliationProcessor processor,
            FinanceReconciliationRuntimeSettingService runtimeSettingService
    ) {
        this.processor = processor;
        this.runtimeSettingService = runtimeSettingService;
    }

    @Scheduled(
            fixedDelayString = "${shop.finance.reconciliation.worker-delay:30s}",
            initialDelayString = "${shop.finance.reconciliation.worker-delay:30s}"
    )
    public void processPendingBatch() {
        if (!runtimeSettingService.workerEnabledFailClosed()) {
            return;
        }
        processor.processNext();
    }
}
