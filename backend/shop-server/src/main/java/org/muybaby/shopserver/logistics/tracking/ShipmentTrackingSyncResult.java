package org.muybaby.shopserver.logistics.tracking;

import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathResult;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryResult;

public record ShipmentTrackingSyncResult(
        WechatTrackingQueryResult queryResult,
        WechatTrackingPathResult pathResult
) {
}
