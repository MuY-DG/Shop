package org.muybaby.shopserver.analytics.dto;

import java.time.Instant;

public record AnalyticsEventBatchResponse(
        int acceptedCount,
        int duplicateCount,
        Instant receivedAt
) {
}
