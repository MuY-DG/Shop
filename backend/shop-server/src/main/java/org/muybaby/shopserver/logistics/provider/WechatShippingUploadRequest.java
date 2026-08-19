package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;

import java.util.List;

public record WechatShippingUploadRequest(
        Long orderId,
        String transactionId,
        String openid,
        LogisticsType logisticsType,
        DeliveryMode deliveryMode,
        boolean allDelivered,
        String uploadTime,
        List<WechatShippingItem> shippingList
) {
    public WechatShippingUploadRequest {
        shippingList = shippingList == null ? null : List.copyOf(shippingList);
    }

    public WechatShippingUploadRequest(
            Long orderId,
            String transactionId,
            String openid,
            LogisticsType logisticsType,
            DeliveryMode deliveryMode,
            String uploadTime,
            List<WechatShippingItem> shippingList
    ) {
        this(orderId, transactionId, openid, logisticsType, deliveryMode, true, uploadTime, shippingList);
    }
}
