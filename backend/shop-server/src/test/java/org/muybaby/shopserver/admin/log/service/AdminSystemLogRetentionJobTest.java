package org.muybaby.shopserver.admin.log.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.log.AdminSystemLogRetentionProperties;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminSystemLogRetentionJobTest {

    @Test
    void calculatesCutoffInAsiaShanghaiInsteadOfJvmDefaultTimezone() {
        Instant instant = Instant.parse("2026-01-01T16:30:00Z");

        assertThat(AdminSystemLogRetentionJob.cutoffAt(instant, 400))
                .isEqualTo(LocalDateTime.of(2024, 11, 28, 0, 30));
        assertThat(AdminSystemLogRetentionJob.RETENTION_ZONE.getId()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void disabledRetentionDoesNotDeleteLogs() {
        AdminSystemLogRetentionService service = mock(AdminSystemLogRetentionService.class);
        AdminSystemLogRetentionJob job = new AdminSystemLogRetentionJob(
                new AdminSystemLogRetentionProperties(false, 400, 5_000, 100),
                service
        );

        job.cleanExpiredLogs();

        verify(service, never()).deleteBatchBefore(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void cutoffCalculationFailureDoesNotEscapeTheScheduledJob() {
        AdminSystemLogRetentionService service = mock(AdminSystemLogRetentionService.class);
        AdminSystemLogRetentionJob job = new AdminSystemLogRetentionJob(
                new AdminSystemLogRetentionProperties(true, 400, 5_000, 100),
                service
        ) {
            @Override
            Instant currentInstant() {
                throw new IllegalStateException("clock unavailable");
            }
        };

        assertThatCode(job::cleanExpiredLogs).doesNotThrowAnyException();
        verifyNoInteractions(service);
    }
}
