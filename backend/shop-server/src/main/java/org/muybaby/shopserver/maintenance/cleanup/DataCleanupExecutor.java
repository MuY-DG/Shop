package org.muybaby.shopserver.maintenance.cleanup;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public interface DataCleanupExecutor {

    DataCleanupTaskCode taskCode();

    int execute(DataCleanupTaskSetting setting);

    default int execute(
            DataCleanupTaskSetting setting,
            BooleanSupplier leaseActive
    ) {
        Objects.requireNonNull(leaseActive, "leaseActive");
        return leaseActive.getAsBoolean() ? execute(setting) : 0;
    }
}
