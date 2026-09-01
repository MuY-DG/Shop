package org.muybaby.shopserver.aftersale.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminRefundProviderAttemptResponse(
        @JsonStringId Long id,
        @JsonStringId Long refundOrderId,
        Long afterSaleId,
        Long orderId,
        String outTradeNo,
        String outRefundNo,
        String attemptType,
        String source,
        String result,
        Integer providerHttpStatus,
        String providerErrorCode,
        String providerStatus,
        String decision,
        String requestId,
        LocalDateTime createdAt
) {
}
