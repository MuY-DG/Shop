package org.muybaby.shopserver.aftersale.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public record AdminAfterSaleSummaryResponse(
        Long id,
        Long orderId,
        String orderNo,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String afterSaleType,
        String status,
        String reason,
        Long requestedAmountCent,
        LocalDateTime createdAt
) {
}
