package org.muybaby.shopserver.admin.log.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AdminSystemLogRetentionJobTest {

    @Test
    void calculatesCutoffAsAnExactUtcInstant() {
        Instant instant = Instant.parse("2026-01-01T16:30:00Z");

        assertThat(AdminSystemLogRetentionJob.cutoffAt(instant, 400))
                .isEqualTo(LocalDateTime.of(2024, 11, 27, 16, 30));
    }

    @Test
    void executesExactlyOneDatabaseConfiguredBatch() {
        AdminSystemLogRetentionService service = mock(AdminSystemLogRetentionService.class);
        AdminSystemLogRetentionJob job = new AdminSystemLogRetentionJob(
                service,
                14
        ) {
            @Override
            Instant currentInstant() {
                return Instant.parse("2026-01-01T16:30:00Z");
            }
        };
        LocalDateTime auditCutoff = LocalDateTime.of(2024, 11, 27, 16, 30);
        LocalDateTime requestCutoff = LocalDateTime.of(2025, 12, 18, 16, 30);
        when(service.deleteExpiredBatch(auditCutoff, requestCutoff, 37)).thenReturn(37);

        int processed = job.execute(setting(400, 37));

        assertThat(processed).isEqualTo(37);
        assertThat(job.taskCode()).isEqualTo(DataCleanupTaskCode.ADMIN_SYSTEM_LOG);
        verify(service, only()).deleteExpiredBatch(auditCutoff, requestCutoff, 37);
    }

    private DataCleanupTaskSetting setting(int retentionDays, int batchSize) {
        return new DataCleanupTaskSetting(
                DataCleanupTaskCode.ADMIN_SYSTEM_LOG,
                true,
                retentionDays,
                batchSize,
                "0 45 3 * * *",
                "Asia/Shanghai",
                60,
                null,
                null,
                0L,
                0L,
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
