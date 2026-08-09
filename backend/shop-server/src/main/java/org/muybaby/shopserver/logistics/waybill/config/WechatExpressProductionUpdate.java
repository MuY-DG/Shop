package org.muybaby.shopserver.logistics.waybill.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WechatExpressProductionUpdate(
        @NotNull @Size(max = 128) String deliveryId,
        @NotNull @Size(max = 128) String deliveryName,
        @Size(max = 128) String bizId,
        @NotNull Boolean clearBizId,
        @Min(0) Integer serviceType,
        @NotNull @Size(max = 128) String serviceName
) {
}
