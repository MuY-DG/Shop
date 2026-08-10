package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTimeoutScanProperties;
import org.muybaby.shopserver.payment.config.PaymentTimeoutSchedulingConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CreatedOrderTimeoutCloseSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class);

    @Test
    void disabledPaymentTimeoutSwitchDoesNotRegisterCreatedOrderScheduler() {
        contextRunner
                .withPropertyValues("shop.pay.timeout-scan-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CreatedOrderTimeoutCloseScheduler.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
                });
    }

    @Test
    void enabledSchedulerUsesTheSharedTimeoutBatchBound() {
        contextRunner
                .withPropertyValues(
                        "shop.pay.timeout-scan-enabled=true",
                        "shop.pay.timeout-scan-delay=1h",
                        "shop.pay.timeout-scan-initial-delay=1h",
                        "shop.pay.timeout-scan-batch-size=7"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CreatedOrderTimeoutCloseScheduler.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);

                    context.getBean(CreatedOrderTimeoutCloseScheduler.class).runOnce();

                    verify(context.getBean(CreatedOrderTimeoutCloseService.class))
                            .closeExpiredCreatedOrders(7);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentTimeoutScanProperties.class)
    @Import({PaymentTimeoutSchedulingConfiguration.class, CreatedOrderTimeoutCloseScheduler.class})
    static class SchedulerTestConfiguration {

        @Bean
        CreatedOrderTimeoutCloseService createdOrderTimeoutCloseService() {
            return mock(CreatedOrderTimeoutCloseService.class);
        }
    }
}
