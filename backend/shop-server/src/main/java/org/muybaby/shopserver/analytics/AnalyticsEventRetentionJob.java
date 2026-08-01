package org.muybaby.shopserver.analytics;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupExecutor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class AnalyticsEventRetentionJob implements DataCleanupExecutor {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AnalyticsEventRetentionService retentionService;

    public AnalyticsEventRetentionJob(AnalyticsEventRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Override
    public DataCleanupTaskCode taskCode() {
        return DataCleanupTaskCode.ANALYTICS_EVENT;
    }

    @Override
    public int execute(DataCleanupTaskSetting setting) {
        LocalDate cutoffDate = LocalDate.now(BUSINESS_ZONE).minusDays(setting.retentionDays());
        return retentionService.deleteBatchBefore(cutoffDate, setting.batchSize());
    }
}
