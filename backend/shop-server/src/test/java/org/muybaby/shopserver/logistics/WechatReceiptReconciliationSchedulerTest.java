package org.muybaby.shopserver.logistics;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.logistics.service.WechatReceiptReconciliationScheduler;
import org.muybaby.shopserver.logistics.service.WechatReceiptReconciliationService;
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

class WechatReceiptReconciliationSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class);

    @Test
    void disabledSwitchDoesNotRegisterScheduler() {
        contextRunner
                .withPropertyValues("shop.wechat.shipping.receipt-reconciliation.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WechatReceiptReconciliationScheduler.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
                });
    }

    @Test
    void enabledSchedulerBindsLimitsAndInvokesConfiguredBatch() {
        contextRunner
                .withPropertyValues(
                        "shop.wechat.shipping.receipt-reconciliation.enabled=true",
                        "shop.wechat.shipping.receipt-reconciliation.delay=1h",
                        "shop.wechat.shipping.receipt-reconciliation.initial-delay=1h",
                        "shop.wechat.shipping.receipt-reconciliation.batch-size=7",
                        "shop.wechat.shipping.receipt-reconciliation.min-shipped-age=2h",
                        "shop.wechat.shipping.receipt-reconciliation.recheck-interval=45m",
                        "shop.wechat.shipping.receipt-reconciliation.claim-timeout=3m"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(WechatReceiptReconciliationScheduler.class);
                    WechatReceiptReconciliationProperties properties = context.getBean(
                            WechatReceiptReconciliationProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.delay()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.batchSize()).isEqualTo(7);
                    assertThat(properties.minShippedAge()).isEqualTo(Duration.ofHours(2));
                    assertThat(properties.recheckInterval()).isEqualTo(Duration.ofMinutes(45));
                    assertThat(properties.claimTimeout()).isEqualTo(Duration.ofMinutes(3));
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);

                    context.getBean(WechatReceiptReconciliationScheduler.class).runOnce();

                    verify(context.getBean(WechatReceiptReconciliationService.class))
                            .reconcilePendingReceipts(7);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WechatReceiptReconciliationProperties.class)
    @Import({
            PaymentTimeoutSchedulingConfiguration.class,
            WechatReceiptReconciliationScheduler.class
    })
    static class SchedulerTestConfiguration {

        @Bean
        WechatReceiptReconciliationService reconciliationService() {
            return mock(WechatReceiptReconciliationService.class);
        }
    }
}
