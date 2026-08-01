package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupExecutor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Component
public class CustomerServiceRetentionJob implements DataCleanupExecutor {

    private final CustomerServiceRetentionService retentionService;

    public CustomerServiceRetentionJob(CustomerServiceRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Override
    public DataCleanupTaskCode taskCode() {
        return DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE;
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
        return LocalDateTime.ofInstant(now.minus(retentionDays, ChronoUnit.DAYS), ZoneOffset.UTC);
    }
}
