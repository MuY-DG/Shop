package org.muybaby.shopserver.logistics.tracking.dto;

import org.muybaby.shopserver.logistics.tracking.WechatLogisticsStatus;
import org.muybaby.shopserver.logistics.tracking.WechatTrackingSyncStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ShipmentTrackingResponse(
        long shipmentId,
        long orderId,
        String carrierCode,
        String carrierName,
        String trackingNo,
        boolean querySupported,
        WechatTrackingSyncStatus querySyncStatus,
        WechatLogisticsStatus logisticsStatus,
        String logisticsStatusText,
        String queryErrorCode,
        String queryErrorMessage,
        boolean pathSupported,
        WechatTrackingSyncStatus pathSyncStatus,
        String pathErrorCode,
        String pathErrorMessage,
        boolean officialViewAvailable,
        List<ShipmentTrackingEventResponse> pathItems,
        LocalDateTime lastAttemptAt,
        LocalDateTime lastSyncedAt
) {
}
