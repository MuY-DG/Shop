package org.muybaby.shopserver.logistics.waybill.provider;

public record WechatElectronicWaybillTestUpdateRequest(
        Long localRecordId,
        String bizId,
        String providerOrderId,
        String deliveryId,
        String waybillId,
        long actionTime,
        int actionType,
        String actionMessage
) {
}
