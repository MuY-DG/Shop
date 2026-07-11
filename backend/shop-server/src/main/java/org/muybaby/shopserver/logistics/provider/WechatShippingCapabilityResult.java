package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.WechatShippingCapabilityState;

import java.util.Objects;

public record WechatShippingCapabilityResult(
        WechatShippingCapabilityState state,
        Boolean tradeManaged,
        String errorCode,
        String errorMessage
) {
    public WechatShippingCapabilityResult {
        Objects.requireNonNull(state, "state");
    }

    public static WechatShippingCapabilityResult available() {
        return new WechatShippingCapabilityResult(
                WechatShippingCapabilityState.AVAILABLE, true, null, null
        );
    }

    public static WechatShippingCapabilityResult unmanaged() {
        return new WechatShippingCapabilityResult(
                WechatShippingCapabilityState.UNAVAILABLE,
                false,
                "TRADE_NOT_MANAGED",
                "WeChat shipping capability is unavailable"
        );
    }

    public static WechatShippingCapabilityResult unavailable(String errorCode, String errorMessage) {
        return new WechatShippingCapabilityResult(
                WechatShippingCapabilityState.UNAVAILABLE, null, errorCode, errorMessage
        );
    }

    public static WechatShippingCapabilityResult unknown(String errorCode, String errorMessage) {
        return new WechatShippingCapabilityResult(
                WechatShippingCapabilityState.UNKNOWN, null, errorCode, errorMessage
        );
    }
}
