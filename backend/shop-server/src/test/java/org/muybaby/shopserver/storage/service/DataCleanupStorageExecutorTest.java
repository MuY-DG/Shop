package org.muybaby.shopserver.storage.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataCleanupStorageExecutorTest {

    @Test
    void storageExecutorUsesDatabaseBatchAndGraceAndReportsAttemptedCount() {
        StorageAssetCleanupService service = mock(StorageAssetCleanupService.class);
        StorageAssetCleanupJob job = new StorageAssetCleanupJob(service);
        when(service.cleanupExpiredAssets(
                eq(41), eq(Duration.ofMinutes(45)), eq(7L), any(BooleanSupplier.class)))
                .thenReturn(new StorageAssetCleanupService.CleanupBatchResult(41, 39));

        int processed = job.execute(setting(
                DataCleanupTaskCode.STORAGE_ASSET,
                null,
                41,
                45,
                7L
        ));

        assertThat(processed).isEqualTo(41);
        assertThat(job.taskCode()).isEqualTo(DataCleanupTaskCode.STORAGE_ASSET);
        verify(service, only()).cleanupExpiredAssets(
                eq(41), eq(Duration.ofMinutes(45)), eq(7L), any(BooleanSupplier.class));
    }

    @Test
    void directUploadExecutorUsesDatabaseBatchAndRetention() {
        DirectUploadService service = mock(DirectUploadService.class);
        DirectUploadSessionCleanupJob job = new DirectUploadSessionCleanupJob(service);
        when(service.cleanupExpiredSessions(
                eq(29), eq(7), eq(8L), any(BooleanSupplier.class))).thenReturn(29);

        int processed = job.execute(setting(
                DataCleanupTaskCode.DIRECT_UPLOAD_SESSION,
                7,
                29,
                null,
                8L
        ));

        assertThat(processed).isEqualTo(29);
        assertThat(job.taskCode()).isEqualTo(DataCleanupTaskCode.DIRECT_UPLOAD_SESSION);
        verify(service, only()).cleanupExpiredSessions(
                eq(29), eq(7), eq(8L), any(BooleanSupplier.class));
    }

    private DataCleanupTaskSetting setting(
            DataCleanupTaskCode taskCode,
            Integer retentionDays,
            int batchSize,
            Integer uploadGraceMinutes,
            long runSequence
    ) {
        return new DataCleanupTaskSetting(
                taskCode,
                true,
                retentionDays,
                batchSize,
                "0 */10 * * * *",
                "Asia/Shanghai",
                60,
                uploadGraceMinutes,
                null,
                0L,
                runSequence,
                null,
                null,
                null,
                "NEVER",
                0,
                "",
                null
        );
    }
}
