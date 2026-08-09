package org.muybaby.shopserver.logistics.waybill.provider;

public record WechatElectronicWaybillCancelRequest(
        Long localRecordId,
        String providerOrderId,
        String openid,
        String deliveryId,
        String waybillId
) {
}
