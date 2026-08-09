package org.muybaby.shopserver.logistics.waybill.config;

public record WechatExpressEffectiveConfig(
        WechatExpressMode mode,
        boolean messageEnabled,
        WechatExpressSender sender,
        WechatExpressAccount account,
        WechatExpressParcel defaultParcel,
        long revision
) {
}
