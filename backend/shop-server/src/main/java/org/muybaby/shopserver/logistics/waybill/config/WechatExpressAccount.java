package org.muybaby.shopserver.logistics.waybill.config;

public record WechatExpressAccount(
        String deliveryId,
        String deliveryName,
        String bizId,
        Integer serviceType,
        String serviceName
) {
}
