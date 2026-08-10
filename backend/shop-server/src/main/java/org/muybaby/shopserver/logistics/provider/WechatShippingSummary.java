package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;

import java.util.List;

public record WechatShippingSummary(
        LogisticsType logisticsType,
        DeliveryMode deliveryMode,
        boolean finishShipping,
        List<WechatShippingFact> shippingList
) {
    public WechatShippingSummary {
        shippingList = shippingList == null ? List.of() : List.copyOf(shippingList);
    }
}
