package org.muybaby.shopserver.maintenance.cleanup.dto;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;

import java.time.LocalDateTime;

public record DataCleanupTaskResponse(
        DataCleanupTaskCode taskCode,
        String title,
        String description,
        boolean enabled,
        Integer retentionDays,
        Integer minRetentionDays,
        Integer maxRetentionDays,
        int batchSize,
        int maxBatchSize,
        String cronExpression,
        String zoneId,
        int batchIntervalSeconds,
        Integer uploadPendingGraceMinutes,
        Boolean retainReviews,
        LocalDateTime nextRunAt,
        LocalDateTime lastStartedAt,
        LocalDateTime lastCompletedAt,
        String lastStatus,
        int lastProcessedCount,
        String lastError,
        LocalDateTime updatedAt
) {
}
