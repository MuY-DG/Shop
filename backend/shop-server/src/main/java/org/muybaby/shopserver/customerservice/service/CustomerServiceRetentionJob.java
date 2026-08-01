package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.customerservice.CustomerServiceRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Component
public class CustomerServiceRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceRetentionJob.class);

    private final CustomerServiceRetentionProperties properties;
    private final CustomerServiceRetentionService retentionService;

    public CustomerServiceRetentionJob(
            CustomerServiceRetentionProperties properties,
            CustomerServiceRetentionService retentionService
    ) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(
            cron = "${shop.customer-service.retention.cron:0 15 4 * * *}",
            zone = "Asia/Shanghai"
    )
    public void cleanExpiredMessages() {
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
            log.info("Customer-service retention reached the configured batch cap; remaining rows will continue next run");
        } catch (RuntimeException ex) {
            log.warn("Customer-service retention cleanup failed; business traffic is unaffected", ex);
        }
    }

    Instant currentInstant() {
        return Instant.now();
    }

    static LocalDateTime cutoffAt(Instant now, int retentionDays) {
        return LocalDateTime.ofInstant(now.minus(retentionDays, ChronoUnit.DAYS), ZoneOffset.UTC);
    }
}
