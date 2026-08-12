package org.muybaby.shopserver.finance.reconciliation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminFinanceReconciliationRuntimeResponse(
        boolean workerEnabled,
        boolean dailyEnabled,
        boolean runtimePersisted,
        long version,
        boolean defaultWorkerEnabled,
        boolean defaultDailyEnabled,
        String reason,
        @JsonStringId Long updatedBy,
        LocalDateTime updatedAt,
        boolean paymentCredentialsReady,
        boolean privateStorageReady,
        boolean workerReady,
        boolean dailyReady,
        long pendingBatches,
        long runningBatches,
        long retryWaitBatches,
        long failedBatches,
        long openDifferences
) {
}
