package org.muybaby.shopserver.finance.reconciliation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminReconciliationResolutionAuditResponse(
        @JsonStringId Long id,
        @JsonStringId Long differenceId,
        String fromStatus,
        String toStatus,
        String action,
        String resolutionCode,
        String reason,
        @JsonStringId Long operatorId,
        LocalDateTime createdAt
) {
}
