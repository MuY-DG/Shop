package org.muybaby.shopserver.storage.compression.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.YearMonth;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminImageCompressionConfigResponse(
        boolean requestedEnabled,
        boolean effectiveEnabled,
        String configSource,
        boolean persisted,
        String defaultConfigSource,
        boolean keyConfigured,
        String apiKeyMasked,
        String outputFormat,
        boolean preserveMetadata,
        int monthlyLimit,
        Integer compressionCount,
        Integer remainingCount,
        YearMonth quotaPeriod,
        LocalDateTime lastCheckedAt,
        String autoDisabledReason,
        LocalDateTime updatedAt
) {
}
