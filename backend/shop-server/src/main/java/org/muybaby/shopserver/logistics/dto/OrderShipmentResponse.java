package org.muybaby.shopserver.logistics.dto;

import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationStatus;

import java.time.LocalDateTime;

public record OrderShipmentResponse(
        Long shipmentId, Long orderId,
        LogisticsType logisticsType, DeliveryMode deliveryMode,
        String itemDesc,
        String expressCompanyCode, String expressCompanyName, String trackingNo,
        ShipmentSource shipmentSource, Long electronicWaybillId,
        String shipmentNote, String localShipmentStatus,
        WechatProviderMode wechatProviderMode,
        WechatShippingUploadStatus wechatUploadStatus,
        String wechatErrorCode, String wechatErrorMessage,
        boolean waybillTrackingSupported,
        WaybillRegistrationKind waybillRegistrationKind,
        WaybillRegistrationStatus waybillRegistrationStatus,
        String waybillRegistrationMessage,
        int retryCount,
        LocalDateTime shippedAt, String uploadTime,
        LocalDateTime wechatUploadedAt, LocalDateTime lastAttemptAt
) {
}
