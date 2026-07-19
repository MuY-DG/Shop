package org.muybaby.shopserver.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record AnalyticsEventRequest(
        @NotBlank @Size(max = 64) String clientEventId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 32) String eventType,
        @NotNull Instant occurredAt,
        @Size(max = 160) String pagePath,
        @Size(max = 160) String sourcePage,
        @Size(max = 32) String entryScene,
        @Size(max = 80) String searchKeyword,
        @Size(max = 20) String checkoutSource,
        @Positive Long spuId,
        @Positive Long skuId,
        @Positive Integer quantity
) {
}
