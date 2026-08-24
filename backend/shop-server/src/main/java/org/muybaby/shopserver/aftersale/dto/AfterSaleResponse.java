package org.muybaby.shopserver.aftersale.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AfterSaleResponse(
        Long id,
        String afterSaleNo,
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
        Long reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        List<Long> evidenceFileIds,
        List<AfterSaleEvidenceFileResponse> evidenceFiles,
        RefundOrderResponse refundOrder,
        List<AfterSaleItemResponse> items,
        AfterSaleReturnResponse returnInfo,
        List<String> allowedActions
) {
    public AfterSaleResponse(
            Long id,
            String afterSaleNo,
            Long orderId,
            String orderNo,
            Long userId,
            String userNickname,
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
            List<AfterSaleEvidenceFileResponse> evidenceFiles,
            RefundOrderResponse refundOrder
    ) {
        this(
                id, afterSaleNo, orderId, orderNo, userId, userNickname,
                afterSaleType, status, reason, description,
                requestedAmountCent, approvedAmountCent, auditNote,
                reviewedBy, reviewedAt, createdAt,
                evidenceFileIds, evidenceFiles, refundOrder,
                List.of(), null, List.of());
    }
}
