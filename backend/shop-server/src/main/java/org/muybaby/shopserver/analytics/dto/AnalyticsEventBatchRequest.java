package org.muybaby.shopserver.analytics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AnalyticsEventBatchRequest(
        @NotBlank @Size(max = 64) String visitorId,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid AnalyticsEventRequest> events
) {
}
