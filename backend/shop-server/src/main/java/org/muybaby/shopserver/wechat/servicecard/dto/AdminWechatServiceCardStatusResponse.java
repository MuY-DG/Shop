package org.muybaby.shopserver.wechat.servicecard.dto;

public record AdminWechatServiceCardStatusResponse(
        boolean captureEnabled,
        boolean workerEnabled,
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
        long failedDeliveries
) {
}
