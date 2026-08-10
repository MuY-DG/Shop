package org.muybaby.shopserver.finance.reconciliation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminReconciliationDifferenceResponse(
        @JsonStringId Long id,
        @JsonStringId Long batchId,
        String diffKey,
        String type,
        String severity,
        String status,
        String transactionId,
        String outTradeNo,
        String refundId,
        String outRefundNo,
        @JsonStringId Long orderId,
        Long providerAmountCent,
        Long localAmountCent,
        String providerStatus,
        String localStatus,
        long version,
        String resolutionCode,
        String resolutionReason,
        @JsonStringId Long resolvedBy,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String candidateContentSha256,
        Long candidateSizeBytes,
        boolean candidateSourceAvailable
) {
}
