package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupExecutor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

@Component
public class DirectUploadSessionCleanupJob implements DataCleanupExecutor {

    private final DirectUploadService directUploadService;

    public DirectUploadSessionCleanupJob(DirectUploadService directUploadService) {
        this.directUploadService = directUploadService;
    }

    @Override
    public DataCleanupTaskCode taskCode() {
        return DataCleanupTaskCode.DIRECT_UPLOAD_SESSION;
    }

    @Override
    public int execute(DataCleanupTaskSetting setting) {
        return execute(setting, () -> true);
    }

    @Override
    public int execute(
            DataCleanupTaskSetting setting,
            BooleanSupplier leaseActive
    ) {
        return directUploadService.cleanupExpiredSessions(
                setting.batchSize(),
                setting.retentionDays(),
                setting.runSequence(),
                leaseActive
        );
    }
}
