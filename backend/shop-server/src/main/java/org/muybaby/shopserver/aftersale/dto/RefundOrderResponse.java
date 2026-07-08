package org.muybaby.shopserver.aftersale.dto;

import java.time.LocalDateTime;

public record RefundOrderResponse(
        Long id,
        Long afterSaleId,
        Long orderId,
        Long paymentOrderId,
        String outRefundNo,
        String refundId,
        Long refundAmountCent,
        String status,
        String callbackStatus,
        String lastErrorCode,
        String lastErrorMessage,
        LocalDateTime requestedAt,
        LocalDateTime successAt
) {
}
