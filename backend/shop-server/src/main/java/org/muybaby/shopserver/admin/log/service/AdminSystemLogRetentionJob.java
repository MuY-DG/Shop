package org.muybaby.shopserver.admin.log.service;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupExecutor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class AdminSystemLogRetentionJob implements DataCleanupExecutor {

    private final AdminSystemLogRetentionService retentionService;
    private final int requestRetentionDays;

    public AdminSystemLogRetentionJob(
            AdminSystemLogRetentionService retentionService,
            @Value("${shop.admin-system-log.request-retention-days:14}") int requestRetentionDays
    ) {
        if (requestRetentionDays < 1) {
            throw new IllegalArgumentException("Request log retention days must be positive");
        }
        this.retentionService = retentionService;
        this.requestRetentionDays = requestRetentionDays;
    }

    @Override
    public DataCleanupTaskCode taskCode() {
        return DataCleanupTaskCode.ADMIN_SYSTEM_LOG;
    }

    @Override
    public int execute(DataCleanupTaskSetting setting) {
        Instant now = currentInstant();
        LocalDateTime auditCutoff = cutoffAt(now, setting.retentionDays());
        LocalDateTime requestCutoff = cutoffAt(now, requestRetentionDays);
        return retentionService.deleteExpiredBatch(
                auditCutoff,
                requestCutoff,
                setting.batchSize()
        );
    }

    Instant currentInstant() {
        return Instant.now();
    }

    static LocalDateTime cutoffAt(Instant now, int retentionDays) {
        return LocalDateTime.ofInstant(now.minus(retentionDays, java.time.temporal.ChronoUnit.DAYS), ZoneOffset.UTC);
    }
}
