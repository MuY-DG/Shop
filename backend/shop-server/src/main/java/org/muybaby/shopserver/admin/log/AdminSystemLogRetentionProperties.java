package org.muybaby.shopserver.admin.log;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.admin-system-log.retention")
public record AdminSystemLogRetentionProperties(
        Boolean enabled,
        Integer days,
        Integer batchSize,
        Integer maxBatchesPerRun
) {

    private static final int DEFAULT_DAYS = 400;
    private static final int MAX_DAYS = 3_650;
    private static final int DEFAULT_BATCH_SIZE = 5_000;
    private static final int MAX_BATCH_SIZE = 50_000;
    private static final int DEFAULT_MAX_BATCHES_PER_RUN = 100;
    private static final int MAX_BATCHES_PER_RUN = 1_000;

    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public int effectiveDays() {
        return days == null || days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
    }

    public int effectiveBatchSize() {
        return batchSize == null || batchSize < 1
                ? DEFAULT_BATCH_SIZE
                : Math.min(batchSize, MAX_BATCH_SIZE);
    }

    public int effectiveMaxBatchesPerRun() {
        return maxBatchesPerRun == null || maxBatchesPerRun < 1
                ? DEFAULT_MAX_BATCHES_PER_RUN
                : Math.min(maxBatchesPerRun, MAX_BATCHES_PER_RUN);
    }
}
