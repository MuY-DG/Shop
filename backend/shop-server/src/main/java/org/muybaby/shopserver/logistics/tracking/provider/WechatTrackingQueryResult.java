package org.muybaby.shopserver.logistics.tracking.provider;

import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;

public record WechatTrackingQueryResult(
        WechatProviderOutcome outcome,
        Integer logisticsStatus,
        String errorCode,
        String errorMessage
) {
    public static WechatTrackingQueryResult success(int logisticsStatus) {
        return new WechatTrackingQueryResult(
                WechatProviderOutcome.SUCCESS, logisticsStatus, null, null
        );
    }

    public static WechatTrackingQueryResult failure(
            WechatProviderOutcome outcome,
            String errorCode,
            String errorMessage
    ) {
        return new WechatTrackingQueryResult(outcome, null, errorCode, errorMessage);
    }
}
