package org.muybaby.shopserver.admin.log.service;

import org.muybaby.shopserver.admin.log.AdminSystemLogRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class AdminSystemLogRetentionJob {

    static final ZoneId RETENTION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Logger log = LoggerFactory.getLogger(AdminSystemLogRetentionJob.class);

    private final AdminSystemLogRetentionProperties properties;
    private final AdminSystemLogRetentionService retentionService;

    public AdminSystemLogRetentionJob(
            AdminSystemLogRetentionProperties properties,
            AdminSystemLogRetentionService retentionService
    ) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(
            cron = "${shop.admin-system-log.retention.cron:0 45 3 * * *}",
            zone = "Asia/Shanghai"
    )
    public void cleanExpiredLogs() {
        try {
            if (!properties.isEnabled()) {
                return;
            }
            LocalDateTime cutoff = cutoffAt(currentInstant(), properties.effectiveDays());
            int batchSize = properties.effectiveBatchSize();
            for (int batch = 0; batch < properties.effectiveMaxBatchesPerRun(); batch++) {
                int deleted = retentionService.deleteBatchBefore(cutoff, batchSize);
                if (deleted < batchSize) {
                    return;
                }
            }
            log.info("Admin system log retention reached the configured batch cap; remaining rows will continue next run");
        } catch (RuntimeException ex) {
            log.warn("Admin system log retention cleanup failed; business traffic is unaffected", ex);
        }
    }

    Instant currentInstant() {
        return Instant.now();
    }

    static LocalDateTime cutoffAt(Instant now, int retentionDays) {
        return LocalDateTime.ofInstant(now, RETENTION_ZONE).minusDays(retentionDays);
    }
}
