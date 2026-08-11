package org.muybaby.shopserver.wechat.servicecard.dto;

public record AdminWechatServiceCardDeliveryQuery(
        Long current,
        Long size,
        Long orderId,
        String state
) {
}
