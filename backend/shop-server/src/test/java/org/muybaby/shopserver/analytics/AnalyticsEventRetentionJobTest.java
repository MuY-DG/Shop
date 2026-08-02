package org.muybaby.shopserver.analytics;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsEventRetentionJobTest {

    @Test
    void executesExactlyOneDatabaseConfiguredBatch() {
        AnalyticsEventRetentionService service = mock(AnalyticsEventRetentionService.class);
        AnalyticsEventRetentionJob job = new AnalyticsEventRetentionJob(service);
        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        LocalDate before = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(400);
        when(service.deleteBatchBefore(org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.eq(23))).thenReturn(23);

        int processed = job.execute(setting(400, 23));

        LocalDate after = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(400);
        assertThat(processed).isEqualTo(23);
        assertThat(job.taskCode()).isEqualTo(DataCleanupTaskCode.ANALYTICS_EVENT);
        verify(service, only()).deleteBatchBefore(cutoff.capture(),
                org.mockito.ArgumentMatchers.eq(23));
        assertThat(cutoff.getValue()).isBetween(before, after);
    }

    private DataCleanupTaskSetting setting(int retentionDays, int batchSize) {
        return new DataCleanupTaskSetting(
                DataCleanupTaskCode.ANALYTICS_EVENT,
                true,
                retentionDays,
                batchSize,
                "0 15 3 * * *",
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
