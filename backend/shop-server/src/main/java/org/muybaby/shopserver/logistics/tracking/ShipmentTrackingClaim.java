package org.muybaby.shopserver.logistics.tracking;

import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathRequest;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryRequest;

public record ShipmentTrackingClaim(
        long shipmentId,
        long orderId,
        String claimToken,
        WechatTrackingQueryRequest queryRequest,
        WechatTrackingPathRequest pathRequest
) {
}
