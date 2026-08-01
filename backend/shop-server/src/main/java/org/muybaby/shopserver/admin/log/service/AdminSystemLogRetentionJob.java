package org.muybaby.shopserver.admin.log.service;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupExecutor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class AdminSystemLogRetentionJob implements DataCleanupExecutor {

    private final AdminSystemLogRetentionService retentionService;

    public AdminSystemLogRetentionJob(AdminSystemLogRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Override
    public DataCleanupTaskCode taskCode() {
        return DataCleanupTaskCode.ADMIN_SYSTEM_LOG;
    }

    @Override
    public int execute(DataCleanupTaskSetting setting) {
        LocalDateTime cutoff = cutoffAt(currentInstant(), setting.retentionDays());
        return retentionService.deleteBatchBefore(cutoff, setting.batchSize());
    }

    Instant currentInstant() {
        return Instant.now();
    }

    static LocalDateTime cutoffAt(Instant now, int retentionDays) {
        return LocalDateTime.ofInstant(now.minus(retentionDays, java.time.temporal.ChronoUnit.DAYS), ZoneOffset.UTC);
    }
}
