package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.time.TimePolicy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class DailyTradeReconciliationScheduler {

    private final FinanceReconciliationCommandService commandService;
    private final FinanceReconciliationRuntimeSettingService runtimeSettingService;
    private final Clock clock;

    public DailyTradeReconciliationScheduler(
            FinanceReconciliationCommandService commandService,
            FinanceReconciliationRuntimeSettingService runtimeSettingService,
            Clock clock
    ) {
        this.commandService = commandService;
        this.runtimeSettingService = runtimeSettingService;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${shop.finance.reconciliation.daily-cron:0 30 10 * * *}",
            zone = "Asia/Shanghai"
    )
    public void enqueuePreviousBusinessDay() {
        if (!runtimeSettingService.dailyEnabledFailClosed()) {
            return;
        }
        LocalDate previousDay = LocalDate.now(clock.withZone(TimePolicy.BUSINESS_ZONE)).minusDays(1);
        commandService.requestDaily(previousDay);
    }
}
