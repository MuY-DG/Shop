package org.muybaby.shopserver.logistics;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.logistics.service.WechatShippingDeliveryScheduler;
import org.muybaby.shopserver.logistics.service.WechatShippingRuntimeSettingService;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadCoordinator;
import org.muybaby.shopserver.logistics.service.WechatShippingUploadStateStore;
import org.muybaby.shopserver.payment.config.PaymentTimeoutSchedulingConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WechatShippingDeliverySchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class);

    @Test
    void schedulerStaysRegisteredWhenLegacyDefaultIsDisabled() {
        contextRunner
                .withPropertyValues("shop.wechat.shipping.delivery.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(WechatShippingDeliveryScheduler.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);

                    context.getBean(WechatShippingDeliveryScheduler.class).runOnce();

                    verify(context.getBean(WechatShippingUploadStateStore.class), never())
                            .reconcileStaleBatch(any(LocalDateTime.class));
                });
    }

    @Test
    void enabledSchedulerRecoversStaleClaimsThenDeliversAndReconcilesConfiguredBatch() {
        contextRunner
                .withPropertyValues(
                        "shop.wechat.shipping.upload-enabled=true",
                        "shop.wechat.shipping.delivery.enabled=true",
                        "shop.wechat.shipping.delivery.delay=1h",
                        "shop.wechat.shipping.delivery.initial-delay=1h",
                        "shop.wechat.shipping.delivery.batch-size=7",
                        "shop.wechat.shipping.delivery.claim-timeout=3m",
                        "shop.wechat.shipping.delivery.max-attempts=4",
                        "shop.wechat.shipping.delivery.retry-backoff=20s",
                        "shop.wechat.shipping.delivery.max-retry-backoff=5m",
                        "shop.wechat.shipping.delivery.unknown-recheck-interval=2m",
                        "shop.wechat.shipping.delivery.not-uploaded-confirmations=3"
                )
                .run(context -> {
                    WechatShippingDeliveryProperties properties = context.getBean(
                            WechatShippingDeliveryProperties.class
                    );
                    assertThat(properties.delay()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.batchSize()).isEqualTo(7);
                    assertThat(properties.claimTimeout()).isEqualTo(Duration.ofMinutes(3));
                    assertThat(properties.maxAttempts()).isEqualTo(4);
                    assertThat(properties.retryBackoff()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(properties.maxRetryBackoff()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.unknownRecheckInterval()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.notUploadedConfirmations()).isEqualTo(3);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);

                    WechatShippingUploadStateStore stateStore = context.getBean(
                            WechatShippingUploadStateStore.class
                    );
                    WechatShippingUploadCoordinator coordinator = context.getBean(
                            WechatShippingUploadCoordinator.class
                    );
                    when(context.getBean(WechatShippingRuntimeSettingService.class)
                            .deliveryEnabledFailClosed()).thenReturn(true);
                    context.getBean(WechatShippingDeliveryScheduler.class).runOnce();

                    var ordered = inOrder(stateStore, coordinator);
                    ordered.verify(stateStore).reconcileStaleBatch(any(LocalDateTime.class));
                    ordered.verify(coordinator).deliverDue(7);
                    ordered.verify(coordinator).reconcileDueUnknown(7);
                });
    }

    @Test
    void uploadDisabledLeavesPersistedWorkUntouched() {
        contextRunner
                .withPropertyValues(
                        "shop.wechat.shipping.upload-enabled=false",
                        "shop.wechat.shipping.delivery.enabled=true",
                        "shop.wechat.shipping.delivery.delay=1h",
                        "shop.wechat.shipping.delivery.initial-delay=1h"
                )
                .run(context -> {
                    WechatShippingUploadStateStore stateStore = context.getBean(
                            WechatShippingUploadStateStore.class
                    );
                    WechatShippingUploadCoordinator coordinator = context.getBean(
                            WechatShippingUploadCoordinator.class
                    );

                    context.getBean(WechatShippingDeliveryScheduler.class).runOnce();

                    verify(stateStore, never()).reconcileStaleBatch(any(LocalDateTime.class));
                    verify(coordinator, never()).deliverDue(anyInt());
                    verify(coordinator, never()).reconcileDueUnknown(anyInt());
                });
    }

    @Test
    void scanFailureIsContainedForTheNextScheduledTick() {
        contextRunner
                .withPropertyValues(
                        "shop.wechat.shipping.upload-enabled=true",
                        "shop.wechat.shipping.delivery.enabled=true",
                        "shop.wechat.shipping.delivery.delay=1h",
                        "shop.wechat.shipping.delivery.initial-delay=1h"
                )
                .run(context -> {
                    WechatShippingUploadStateStore stateStore = context.getBean(
                            WechatShippingUploadStateStore.class
                    );
                    WechatShippingUploadCoordinator coordinator = context.getBean(
                            WechatShippingUploadCoordinator.class
                    );
                    when(context.getBean(WechatShippingRuntimeSettingService.class)
                            .deliveryEnabledFailClosed()).thenReturn(true);
                    when(stateStore.reconcileStaleBatch(any(LocalDateTime.class)))
                            .thenThrow(new IllegalStateException("transient database failure"));

                    assertThatCode(() -> context.getBean(
                            WechatShippingDeliveryScheduler.class
                    ).runOnce()).doesNotThrowAnyException();
                    verify(coordinator, never()).deliverDue(anyInt());
                    verify(coordinator, never()).reconcileDueUnknown(anyInt());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            WechatShippingDeliveryProperties.class
    })
    @Import({
            PaymentTimeoutSchedulingConfiguration.class,
            WechatShippingDeliveryScheduler.class
    })
    static class SchedulerTestConfiguration {

        @Bean
        WechatShippingUploadStateStore stateStore() {
            return mock(WechatShippingUploadStateStore.class);
        }

        @Bean
        WechatShippingUploadCoordinator coordinator() {
            return mock(WechatShippingUploadCoordinator.class);
        }

        @Bean
        WechatShippingRuntimeSettingService runtimeSettingService() {
            return mock(WechatShippingRuntimeSettingService.class);
        }
    }
}
