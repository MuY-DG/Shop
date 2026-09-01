package org.muybaby.shopserver.payment.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTimeoutZSetProperties;
import org.muybaby.shopserver.payment.config.PaymentTimeoutSchedulingConfiguration;
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

class PaymentTimeoutZSetSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class);

    @Test
    void disabledSwitchDoesNotRegisterScheduler() {
        contextRunner
                .withPropertyValues("shop.pay.timeout-zset.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PaymentTimeoutZSetScheduler.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
                });
    }

    @Test
    void enabledSchedulerBindsConfigurationAndPassesBatchSize() {
        contextRunner
                .withPropertyValues(
                        "shop.pay.timeout-zset.enabled=true",
                        "shop.pay.timeout-zset.poll-delay=1h",
                        "shop.pay.timeout-zset.initial-delay=1h",
                        "shop.pay.timeout-zset.batch-size=7",
                        "shop.pay.timeout-zset.retry-delay=45s"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentTimeoutZSetScheduler.class);
                    PaymentTimeoutZSetProperties properties = context.getBean(PaymentTimeoutZSetProperties.class);
                    assertThat(properties.pollDelay()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.batchSize()).isEqualTo(7);
                    assertThat(properties.retryDelay()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);

                    context.getBean(PaymentTimeoutZSetScheduler.class).runOnce();

                    verify(context.getBean(PaymentTimeoutZSetWorker.class)).runOnce(7);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentTimeoutZSetProperties.class)
    @Import({PaymentTimeoutSchedulingConfiguration.class, PaymentTimeoutZSetScheduler.class})
    static class SchedulerTestConfiguration {

        @Bean
        PaymentTimeoutZSetWorker paymentTimeoutZSetWorker() {
            return mock(PaymentTimeoutZSetWorker.class);
        }
    }
}
