package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.time.TimePolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(
        prefix = "shop.finance.reconciliation",
        name = "daily-enabled",
        havingValue = "true"
)
public class DailyTradeReconciliationScheduler {

    private final FinanceReconciliationCommandService commandService;
    private final Clock clock;

    public DailyTradeReconciliationScheduler(
            FinanceReconciliationCommandService commandService,
            Clock clock
    ) {
        this.commandService = commandService;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${shop.finance.reconciliation.daily-cron:0 30 10 * * *}",
            zone = "Asia/Shanghai"
    )
    public void enqueuePreviousBusinessDay() {
        LocalDate previousDay = LocalDate.now(clock.withZone(TimePolicy.BUSINESS_ZONE)).minusDays(1);
        commandService.requestDaily(previousDay);
    }
}
