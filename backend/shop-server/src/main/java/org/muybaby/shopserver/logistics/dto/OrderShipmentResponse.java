package org.muybaby.shopserver.logistics.dto;

import java.time.LocalDateTime;

public record OrderShipmentResponse(
        Long shipmentId,
        Long orderId,
        String expressCompany,
        String trackingNo,
        String shipmentNote,
        String status,
        String wechatUploadStatus,
        String wechatErrorCode,
        String wechatErrorMessage,
        Integer retryCount,
        LocalDateTime shippedAt,
        LocalDateTime wechatUploadedAt
) {
}
