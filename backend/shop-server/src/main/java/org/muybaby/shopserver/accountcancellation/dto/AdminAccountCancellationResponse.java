package org.muybaby.shopserver.accountcancellation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AdminAccountCancellationResponse(
        @JsonStringId Long id,
        @JsonStringId Long userId,
        @JsonStringId Long legalDocumentRevisionId,
        String noticeVersion,
        String noticeContentSha256,
        String channel,
        String miniProgramEnv,
        LocalDateTime identityVerifiedAt,
        List<String> deletedDataCategories,
        List<String> retainedDataCategories,
        LocalDateTime completedAt
) {
}
