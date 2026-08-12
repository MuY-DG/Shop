package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.time.TimePolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceReconciliationRuntimeSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T03:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void installedWorkerChecksDatabaseGateBeforeClaimingWork() {
        TradeReconciliationProcessor processor = mock(TradeReconciliationProcessor.class);
        FinanceReconciliationRuntimeSettingService runtime = mock(
                FinanceReconciliationRuntimeSettingService.class
        );
        TradeReconciliationWorker worker = new TradeReconciliationWorker(processor, runtime);

        when(runtime.workerEnabledFailClosed()).thenReturn(false, true);
        worker.processPendingBatch();
        verify(processor, never()).processNext();

        worker.processPendingBatch();
        verify(processor).processNext();
    }

    @Test
    void installedDailySchedulerChecksDatabaseGateBeforeCreatingPreviousDay() {
        FinanceReconciliationCommandService command = mock(
                FinanceReconciliationCommandService.class
        );
        FinanceReconciliationRuntimeSettingService runtime = mock(
                FinanceReconciliationRuntimeSettingService.class
        );
        DailyTradeReconciliationScheduler scheduler = new DailyTradeReconciliationScheduler(
                command, runtime, CLOCK
        );
        LocalDate previousBusinessDay = LocalDate.now(
                CLOCK.withZone(TimePolicy.BUSINESS_ZONE)
        ).minusDays(1);

        when(runtime.dailyEnabledFailClosed()).thenReturn(false, true);
        scheduler.enqueuePreviousBusinessDay();
        verify(command, never()).requestDaily(previousBusinessDay);

        scheduler.enqueuePreviousBusinessDay();
        verify(command).requestDaily(previousBusinessDay);
    }
}
