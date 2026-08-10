package org.muybaby.shopserver.accountrights.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AccountRightsRequestResponse(
        @JsonStringId Long id,
        @JsonStringId Long userId,
        String userNickname,
        String userStatus,
        String requestType,
        String status,
        String requestNote,
        LocalDateTime identityVerifiedAt,
        String reviewReason,
        String retentionExplanation,
        List<String> retainedDataCategories,
        @JsonStringId Long reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime approvedAt,
        LocalDateTime rejectedAt,
        LocalDateTime withdrawnAt,
        LocalDateTime completedAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public AccountRightsRequestResponse {
        retainedDataCategories = retainedDataCategories == null
                ? List.of()
                : List.copyOf(retainedDataCategories);
    }
}
