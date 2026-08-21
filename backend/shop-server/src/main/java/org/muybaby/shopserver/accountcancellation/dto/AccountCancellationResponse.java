package org.muybaby.shopserver.accountcancellation.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AccountCancellationResponse(
        @JsonStringId Long cancellationId,
        LocalDateTime completedAt
) {
}
