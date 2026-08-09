package org.muybaby.shopserver.logistics.tracking.provider;

import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;

import java.util.List;

public record WechatTrackingPathResult(
        WechatProviderOutcome outcome,
        List<WechatTrackingPathItem> pathItems,
        String errorCode,
        String errorMessage
) {
    public static WechatTrackingPathResult success(List<WechatTrackingPathItem> pathItems) {
        return new WechatTrackingPathResult(
                WechatProviderOutcome.SUCCESS,
                pathItems == null ? List.of() : List.copyOf(pathItems),
                null,
                null
        );
    }

    public static WechatTrackingPathResult failure(
            WechatProviderOutcome outcome,
            String errorCode,
            String errorMessage
    ) {
        return new WechatTrackingPathResult(outcome, List.of(), errorCode, errorMessage);
    }
}
