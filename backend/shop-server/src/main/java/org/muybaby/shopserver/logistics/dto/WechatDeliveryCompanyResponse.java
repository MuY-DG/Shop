package org.muybaby.shopserver.logistics.dto;

import java.time.LocalDateTime;

public record WechatDeliveryCompanyResponse(
        String deliveryId,
        String deliveryName,
        LocalDateTime syncedAt
) {
}
