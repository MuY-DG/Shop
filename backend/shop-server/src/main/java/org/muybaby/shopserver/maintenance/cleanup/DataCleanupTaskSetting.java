package org.muybaby.shopserver.maintenance.cleanup;

import java.time.LocalDateTime;

public record DataCleanupTaskSetting(
        DataCleanupTaskCode taskCode,
        boolean enabled,
        Integer retentionDays,
        int batchSize,
        String cronExpression,
        String zoneId,
        int batchIntervalSeconds,
        Integer uploadPendingGraceMinutes,
        long configRevision,
        long runSequence,
        LocalDateTime nextRunAt,
        LocalDateTime lastStartedAt,
        LocalDateTime lastCompletedAt,
        String lastStatus,
        int lastProcessedCount,
        String lastError,
        LocalDateTime updatedAt
) {
}
