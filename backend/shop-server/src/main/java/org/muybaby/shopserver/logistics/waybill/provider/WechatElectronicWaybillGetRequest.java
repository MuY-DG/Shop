package org.muybaby.shopserver.logistics.waybill.provider;

public record WechatElectronicWaybillGetRequest(
        Long localRecordId,
        String providerOrderId,
        String openid,
        String deliveryId,
        String waybillId,
        Integer printType
) {
}
