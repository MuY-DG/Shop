package org.muybaby.shopserver.accountrights.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AccountRightsAuditResponse(
        @JsonStringId Long id,
        String action,
        String actorType,
        @JsonStringId Long actorId,
        String fromStatus,
        String toStatus,
        String reason,
        String retentionExplanation,
        List<String> retainedDataCategories,
        LocalDateTime createdAt
) {
    public AccountRightsAuditResponse {
        retainedDataCategories = retainedDataCategories == null
                ? List.of()
                : List.copyOf(retainedDataCategories);
    }
}
