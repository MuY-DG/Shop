package org.muybaby.shopserver.logistics.tracking.provider;

public record WechatTrackingPathItem(
        long actionTime,
        int actionType,
        String actionMessage
) {
}
