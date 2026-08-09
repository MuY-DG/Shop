package org.muybaby.shopserver.logistics.waybill.config;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WechatExpressAccountResponse(
        String deliveryId,
        String deliveryName,
        String bizIdMasked,
        Integer serviceType,
        String serviceName
) {
}
