package org.muybaby.shopserver.logistics.waybill.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WechatExpressConfigUpdateRequest(
        @NotNull @Min(0) Long revision,
        @NotNull WechatExpressMode mode,
        @NotNull Boolean messageEnabled,
        @NotNull @Valid WechatExpressSender sender,
        @NotNull @Valid WechatExpressProductionUpdate production,
        @NotNull @Valid WechatExpressParcel defaultParcel
) {
}
