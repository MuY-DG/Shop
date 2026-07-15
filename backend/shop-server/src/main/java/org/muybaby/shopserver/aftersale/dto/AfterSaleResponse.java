package org.muybaby.shopserver.aftersale.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AfterSaleResponse(
        Long id,
        Long orderId,
        String orderNo,
        @JsonStringId
        Long userId,
        String userNickname,
        String afterSaleType,
        String status,
        String reason,
        String description,
        Long requestedAmountCent,
        Long approvedAmountCent,
        String auditNote,
        @JsonStringId
        Long reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        List<Long> evidenceFileIds,
        List<AfterSaleEvidenceFileResponse> evidenceFiles,
        RefundOrderResponse refundOrder
) {
}
