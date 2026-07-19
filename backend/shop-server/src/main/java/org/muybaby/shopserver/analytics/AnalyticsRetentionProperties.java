package org.muybaby.shopserver.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.analytics.retention")
public record AnalyticsRetentionProperties(
        Boolean enabled,
        Integer days,
        Integer batchSize,
        Integer maxBatchesPerRun
) {
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public int effectiveDays() {
        return days == null || days < 367 ? 400 : days;
    }

    public int effectiveBatchSize() {
        return batchSize == null || batchSize < 1 ? 5_000 : Math.min(batchSize, 50_000);
    }

    public int effectiveMaxBatchesPerRun() {
        return maxBatchesPerRun == null || maxBatchesPerRun < 1 ? 100 : Math.min(maxBatchesPerRun, 1_000);
    }
}
