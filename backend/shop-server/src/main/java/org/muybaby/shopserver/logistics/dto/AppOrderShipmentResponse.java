package org.muybaby.shopserver.logistics.dto;

import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AppOrderShipmentResponse(
        Long shipmentId, Long orderId,
        Integer packageNo, boolean finalShipment,
        LogisticsType logisticsType, DeliveryMode deliveryMode,
        String itemDesc,
        String expressCompanyCode, String expressCompanyName, String trackingNo,
        ShipmentSource shipmentSource, Long electronicWaybillId,
        String localShipmentStatus,
        WechatProviderMode wechatProviderMode,
        WechatShippingUploadStatus wechatUploadStatus,
        String wechatUploadMessage,
        boolean waybillTrackingSupported,
        WaybillRegistrationKind waybillRegistrationKind,
        WaybillRegistrationStatus waybillRegistrationStatus,
        String waybillRegistrationMessage,
        LocalDateTime shippedAt, String uploadTime,
        LocalDateTime wechatUploadedAt,
        List<ShipmentItemResponse> items
) {
    public AppOrderShipmentResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
