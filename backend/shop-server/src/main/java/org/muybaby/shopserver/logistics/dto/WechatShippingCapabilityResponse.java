package org.muybaby.shopserver.logistics.dto;

import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingCapabilityState;

import java.time.OffsetDateTime;

public record WechatShippingCapabilityResponse(
        boolean uploadEnabled,
        WechatProviderMode providerMode,
        WechatShippingCapabilityState state,
        Boolean tradeManaged,
        String errorCode,
        String errorMessage,
        OffsetDateTime checkedAt
) {
}
