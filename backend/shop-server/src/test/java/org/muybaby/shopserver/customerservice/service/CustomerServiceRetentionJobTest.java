package org.muybaby.shopserver.customerservice.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskSetting;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerServiceRetentionJobTest {

    @Test
    void executesExactlyOneDatabaseConfiguredBatch() {
        CustomerServiceRetentionService service = mock(CustomerServiceRetentionService.class);
        CustomerServiceRetentionJob job = new CustomerServiceRetentionJob(service) {
            @Override
            Instant currentInstant() {
                return Instant.parse("2026-01-01T16:30:00Z");
            }
        };
        LocalDateTime cutoff = LocalDateTime.of(2025, 1, 1, 16, 30);
        when(service.deleteBatchBefore(cutoff, 19)).thenReturn(19);

        int processed = job.execute(setting(365, 19));

        assertThat(processed).isEqualTo(19);
        assertThat(job.taskCode()).isEqualTo(DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE);
        verify(service, only()).deleteBatchBefore(cutoff, 19);
    }

    private DataCleanupTaskSetting setting(int retentionDays, int batchSize) {
        return new DataCleanupTaskSetting(
                DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE,
                true,
                retentionDays,
                batchSize,
                "0 15 4 * * *",
                "Asia/Shanghai",
                60,
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
