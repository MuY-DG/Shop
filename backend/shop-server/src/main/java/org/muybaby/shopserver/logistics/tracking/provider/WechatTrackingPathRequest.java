package org.muybaby.shopserver.logistics.tracking.provider;

public record WechatTrackingPathRequest(
        long shipmentId,
        String providerOrderId,
        String openid,
        String deliveryId,
        String waybillId
) {
}
