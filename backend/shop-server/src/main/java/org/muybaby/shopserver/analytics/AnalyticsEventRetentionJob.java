package org.muybaby.shopserver.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class AnalyticsEventRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventRetentionJob.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AnalyticsRetentionProperties properties;
    private final AnalyticsEventRetentionService retentionService;

    public AnalyticsEventRetentionJob(
            AnalyticsRetentionProperties properties,
            AnalyticsEventRetentionService retentionService
    ) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(
            cron = "${shop.analytics.retention.cron:0 15 3 * * *}",
            zone = "Asia/Shanghai"
    )
    public void cleanExpiredEvents() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDate cutoffDate = LocalDate.now(BUSINESS_ZONE).minusDays(properties.effectiveDays());
        int batchSize = properties.effectiveBatchSize();
        try {
            for (int batch = 0; batch < properties.effectiveMaxBatchesPerRun(); batch++) {
                int deleted = retentionService.deleteBatchBefore(cutoffDate, batchSize);
                if (deleted < batchSize) {
                    return;
                }
            }
            log.info("Analytics retention reached the configured batch cap; remaining rows will continue next run");
        } catch (RuntimeException ex) {
            log.warn("Analytics retention cleanup failed; business traffic is unaffected", ex);
        }
    }
}
