package org.muybaby.shopserver.maintenance.cleanup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;

public record DataCleanupTaskUpdateRequest(
        @NotNull DataCleanupTaskCode taskCode,
        @NotNull Boolean enabled,
        Integer retentionDays,
        @NotNull @Min(1) @Max(50_000) Integer batchSize,
        @NotBlank @Size(max = 80) String cronExpression,
        @NotNull @Min(60) @Max(86_400) Integer batchIntervalSeconds,
        Integer uploadPendingGraceMinutes,
        Boolean retainReviews
) {
}
