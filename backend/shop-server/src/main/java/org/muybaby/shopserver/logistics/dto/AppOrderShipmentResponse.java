package org.muybaby.shopserver.logistics.dto;

import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;

import java.time.LocalDateTime;

public record AppOrderShipmentResponse(
        Long shipmentId, Long orderId,
        LogisticsType logisticsType, DeliveryMode deliveryMode,
        String itemDesc,
        String expressCompanyCode, String expressCompanyName, String trackingNo,
        String localShipmentStatus,
        WechatProviderMode wechatProviderMode,
        WechatShippingUploadStatus wechatUploadStatus,
        String wechatUploadMessage,
        LocalDateTime shippedAt, String uploadTime,
        LocalDateTime wechatUploadedAt
) {
}
