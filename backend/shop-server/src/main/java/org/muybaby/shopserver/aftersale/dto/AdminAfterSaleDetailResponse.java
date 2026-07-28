package org.muybaby.shopserver.aftersale.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AdminAfterSaleDetailResponse(
        Long id,
        String afterSaleNo,
        Long orderId,
        String orderNo,
        @JsonStringId Long userId,
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
        AfterSaleOrderContextResponse orderContext
) {

    public static AdminAfterSaleDetailResponse from(
            AfterSaleResponse afterSale,
            AfterSaleOrderContextResponse orderContext
    ) {
        return new AdminAfterSaleDetailResponse(
                afterSale.id(),
                afterSale.afterSaleNo(),
                afterSale.orderId(),
                afterSale.orderNo(),
                afterSale.userId(),
                afterSale.userNickname(),
                afterSale.afterSaleType(),
                afterSale.status(),
                afterSale.reason(),
                afterSale.description(),
                afterSale.requestedAmountCent(),
                afterSale.approvedAmountCent(),
                afterSale.auditNote(),
                afterSale.reviewedBy(),
                afterSale.reviewedAt(),
                afterSale.createdAt(),
                afterSale.evidenceFileIds(),
                afterSale.evidenceFiles(),
                afterSale.refundOrder(),
                orderContext
        );
    }
}
