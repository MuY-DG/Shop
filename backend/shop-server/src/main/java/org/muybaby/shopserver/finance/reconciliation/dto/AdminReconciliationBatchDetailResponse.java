package org.muybaby.shopserver.finance.reconciliation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AdminReconciliationBatchDetailResponse(
        @JsonStringId Long id,
        LocalDate billDate,
        String mchId,
        String status,
        String phase,
        boolean providerHashVerified,
        String contentSha256,
        boolean sourceAvailable,
        long sourceSizeBytes,
        long totalRows,
        long paymentRows,
        long refundRows,
        long differenceCount,
        long openDifferenceCount,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        String lastErrorCode,
        String lastErrorMessage,
        @JsonStringId Long requestedBy,
        LocalDateTime requestedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version,
        long channelPaymentAmountCent,
        long channelRefundAmountCent,
        long localPaymentAmountCent,
        long localRefundAmountCent
) {
}
