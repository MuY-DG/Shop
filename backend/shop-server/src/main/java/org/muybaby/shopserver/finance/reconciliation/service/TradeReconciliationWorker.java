package org.muybaby.shopserver.finance.reconciliation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shop.finance.reconciliation",
        name = "worker-enabled",
        havingValue = "true"
)
public class TradeReconciliationWorker {

    private final TradeReconciliationProcessor processor;

    public TradeReconciliationWorker(TradeReconciliationProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${shop.finance.reconciliation.worker-delay:30s}",
            initialDelayString = "${shop.finance.reconciliation.worker-delay:30s}"
    )
    public void processPendingBatch() {
        processor.processNext();
    }
}
