package org.muybaby.shopserver.logistics.waybill.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WechatExpressConfigResponse(
        WechatExpressMode mode,
        boolean messageEnabled,
        WechatExpressSender sender,
        WechatExpressAccountResponse production,
        WechatExpressAccountResponse effective,
        WechatExpressParcel defaultParcel,
        long revision,
        LocalDateTime updatedAt
) {
}
