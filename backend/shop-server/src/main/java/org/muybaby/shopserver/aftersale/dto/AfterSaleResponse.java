package org.muybaby.shopserver.aftersale.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AfterSaleResponse(
        Long id,
        Long orderId,
        String orderNo,
        Long userId,
        String afterSaleType,
        String status,
        String reason,
        String description,
        Long requestedAmountCent,
        Long approvedAmountCent,
        String auditNote,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        List<Long> evidenceFileIds,
        RefundOrderResponse refundOrder
) {
}
