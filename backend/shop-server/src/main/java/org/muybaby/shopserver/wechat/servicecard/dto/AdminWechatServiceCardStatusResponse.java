package org.muybaby.shopserver.wechat.servicecard.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminWechatServiceCardStatusResponse(
        boolean captureEnabled,
        boolean workerEnabled,
        boolean runtimePersisted,
        long version,
        boolean defaultCaptureEnabled,
        boolean defaultWorkerEnabled,
        String reason,
        @JsonStringId Long updatedBy,
        LocalDateTime updatedAt,
        boolean captureReady,
        boolean templateConfigured,
        boolean imageReady,
        boolean miniProgramCredentialsReady,
        boolean workerReady,
        boolean callbackEnabled,
        boolean callbackReady,
        long blockedCards,
        long pendingDeliveries,
        long sendingDeliveries,
        long unknownDeliveries,
        long failedDeliveries,
        long repairEligibleCount,
        LocalDateTime repairEligibleEarliestPaidAt,
        LocalDateTime repairEligibleLatestPaidAt
) {
}
