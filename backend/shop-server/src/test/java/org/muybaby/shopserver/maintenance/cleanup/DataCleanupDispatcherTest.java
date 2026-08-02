package org.muybaby.shopserver.maintenance.cleanup;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupConfigService.DataCleanupClaim;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataCleanupDispatcherTest {

    @Test
    void claimsExecutesAndCompletesEachDueTask() {
        DataCleanupConfigService configService = mock(DataCleanupConfigService.class);
        DataCleanupExecutor executor = mock(DataCleanupExecutor.class);
        DataCleanupTaskSetting setting = setting();
        DataCleanupClaim claim = new DataCleanupClaim(setting, "lease-token");
        when(executor.taskCode()).thenReturn(DataCleanupTaskCode.ANALYTICS_EVENT);
        when(configService.dueTaskCodes())
                .thenReturn(List.of(DataCleanupTaskCode.ANALYTICS_EVENT));
        when(configService.claim(DataCleanupTaskCode.ANALYTICS_EVENT))
                .thenReturn(Optional.of(claim));
        when(executor.execute(eq(setting), any(BooleanSupplier.class))).thenReturn(17);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
        doReturn(heartbeatFuture).when(taskScheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), any(Duration.class));
        DataCleanupDispatcher dispatcher = new DataCleanupDispatcher(
                configService, List.of(executor), taskScheduler);

        dispatcher.dispatchDueTasks();

        var ordered = inOrder(configService, executor);
        ordered.verify(configService).initializeMissingSchedules();
        ordered.verify(configService).dueTaskCodes();
        ordered.verify(configService).claim(DataCleanupTaskCode.ANALYTICS_EVENT);
        ordered.verify(executor).execute(eq(setting), any(BooleanSupplier.class));
        ordered.verify(configService).complete(claim, 17);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(
                heartbeat.capture(), any(Instant.class), eq(Duration.ofMinutes(5)));
        verify(heartbeatFuture).cancel(false);

        when(configService.renewLease(claim)).thenReturn(true);
        heartbeat.getValue().run();
        verify(configService).renewLease(claim);
    }

    @Test
    void recordsExecutorFailureAndDoesNotCompleteTheClaim() {
        DataCleanupConfigService configService = mock(DataCleanupConfigService.class);
        DataCleanupExecutor executor = mock(DataCleanupExecutor.class);
        DataCleanupTaskSetting setting = setting();
        DataCleanupClaim claim = new DataCleanupClaim(setting, "lease-token");
        IllegalStateException failure = new IllegalStateException("provider unavailable");
        when(executor.taskCode()).thenReturn(DataCleanupTaskCode.ANALYTICS_EVENT);
        when(configService.claim(DataCleanupTaskCode.ANALYTICS_EVENT))
                .thenReturn(Optional.of(claim));
        when(executor.execute(eq(setting), any(BooleanSupplier.class))).thenThrow(failure);
        DataCleanupDispatcher dispatcher = new DataCleanupDispatcher(
                configService, List.of(executor), mock(TaskScheduler.class));

        dispatcher.runOnce(DataCleanupTaskCode.ANALYTICS_EVENT);

        verify(configService).fail(claim, failure);
        verify(configService, never()).complete(any(), anyInt());
    }

    @Test
    void stopsCooperativelyAndDoesNotCompleteAfterLosingTheLease() {
        DataCleanupConfigService configService = mock(DataCleanupConfigService.class);
        DataCleanupExecutor executor = mock(DataCleanupExecutor.class);
        DataCleanupTaskSetting setting = setting();
        DataCleanupClaim claim = new DataCleanupClaim(setting, "lease-token");
        when(executor.taskCode()).thenReturn(DataCleanupTaskCode.ANALYTICS_EVENT);
        when(configService.claim(DataCleanupTaskCode.ANALYTICS_EVENT))
                .thenReturn(Optional.of(claim));
        when(configService.renewLease(claim)).thenReturn(false);
        when(executor.execute(eq(setting), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    BooleanSupplier leaseActive = invocation.getArgument(1);
                    assertThat(leaseActive.getAsBoolean()).isFalse();
                    return 0;
                });
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return heartbeatFuture;
        }).when(taskScheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), any(Duration.class));
        DataCleanupDispatcher dispatcher = new DataCleanupDispatcher(
                configService, List.of(executor), taskScheduler);

        dispatcher.runOnce(DataCleanupTaskCode.ANALYTICS_EVENT);

        verify(configService).fail(eq(claim), any(IllegalStateException.class));
        verify(configService, never()).complete(any(), anyInt());
        verify(heartbeatFuture).cancel(false);
    }

    private DataCleanupTaskSetting setting() {
        return new DataCleanupTaskSetting(
                DataCleanupTaskCode.ANALYTICS_EVENT,
                true,
                400,
                17,
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
