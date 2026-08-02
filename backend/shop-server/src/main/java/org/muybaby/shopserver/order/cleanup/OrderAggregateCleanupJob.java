package org.muybaby.shopserver.order.cleanup;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupExecutor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;

@Component
public class OrderAggregateCleanupJob implements DataCleanupExecutor {

    private final OrderAggregateCleanupService cleanupService;

    public OrderAggregateCleanupJob(OrderAggregateCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Override
    public DataCleanupTaskCode taskCode() {
        return DataCleanupTaskCode.ORDER_AGGREGATE;
    }

    @Override
    public int execute(DataCleanupTaskSetting setting) {
        return execute(setting, () -> true);
    }

    @Override
    public int execute(DataCleanupTaskSetting setting, BooleanSupplier leaseActive) {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(setting.retentionDays());
        return cleanupService.cleanupBatch(
                cutoff,
                setting.batchSize(),
                setting.retainReviews(),
                leaseActive
        );
    }
}
