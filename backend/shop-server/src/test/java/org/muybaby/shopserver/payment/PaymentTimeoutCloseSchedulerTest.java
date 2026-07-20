package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.config.PaymentTimeoutSchedulingConfiguration;
import org.muybaby.shopserver.payment.service.PaymentTimeoutCloseScheduler;
import org.muybaby.shopserver.payment.service.PaymentTimeoutCloseService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentTimeoutCloseSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class);

    @Test
    void disabledSwitchDoesNotRegisterScheduler() {
        contextRunner
                .withPropertyValues("shop.pay.timeout-scan-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PaymentTimeoutCloseScheduler.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
                });
    }

    @Test
    void enabledSchedulerBindsConfigurationRegistersTaskAndPassesBatchSize() {
        contextRunner
                .withPropertyValues(
                        "shop.pay.timeout-scan-enabled=true",
                        "shop.pay.timeout-scan-delay=1h",
                        "shop.pay.timeout-scan-initial-delay=1h",
                        "shop.pay.timeout-scan-batch-size=7",
                        "shop.pay.timeout-scan-claim-timeout=3m"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentTimeoutCloseScheduler.class);
                    PaymentTimeoutScanProperties properties = context.getBean(PaymentTimeoutScanProperties.class);
                    assertThat(properties.timeoutScanEnabled()).isTrue();
                    assertThat(properties.timeoutScanDelay()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.timeoutScanBatchSize()).isEqualTo(7);
                    assertThat(properties.timeoutScanClaimTimeout()).isEqualTo(Duration.ofMinutes(3));
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);

                    context.getBean(PaymentTimeoutCloseScheduler.class).runOnce();

                    verify(context.getBean(PaymentTimeoutCloseService.class)).closeExpiredPayments(7);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentTimeoutScanProperties.class)
    @Import({PaymentTimeoutSchedulingConfiguration.class, PaymentTimeoutCloseScheduler.class})
    static class SchedulerTestConfiguration {

        @Bean
        PaymentTimeoutCloseService paymentTimeoutCloseService() {
            return mock(PaymentTimeoutCloseService.class);
        }
    }
}
