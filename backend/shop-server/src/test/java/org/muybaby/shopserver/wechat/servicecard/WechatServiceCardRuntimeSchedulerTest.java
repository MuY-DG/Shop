package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WechatServiceCardRuntimeSchedulerTest {

    private static final AtomicLong IDS = new AtomicLong(8_900_000_000_000_000L);
    private static final Instant NOW = Instant.parse("2026-08-11T06:00:00Z");

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WechatServiceCardProperties environmentProperties;

    @Autowired
    WechatServiceCardDeliveryScheduler deliveryScheduler;

    @Autowired
    WechatServiceCardRepairScheduler repairScheduler;

    @Test
    void schedulersRemainInstalledWithStaticDefaultsOffAndLeaveWorkUntouched() {
        assertThat(environmentProperties.enabled()).isFalse();
        assertThat(environmentProperties.workerEnabled()).isFalse();
        assertThat(deliveryScheduler).isNotNull();
        assertThat(repairScheduler).isNotNull();

        WechatServiceCardRuntimeSettingService runtime = mock(
                WechatServiceCardRuntimeSettingService.class
        );
        WechatServiceCardDeliveryStore localStore = mock(
                WechatServiceCardDeliveryStore.class
        );
        WechatServiceCardDeliveryCoordinator localCoordinator = mock(
                WechatServiceCardDeliveryCoordinator.class
        );
        when(runtime.workerReadyFailClosed()).thenReturn(false);
        new WechatServiceCardDeliveryScheduler(
                runtime, localStore, localCoordinator, Clock.fixed(NOW, ZoneOffset.UTC)
        ).runOnce();
        verifyNoInteractions(localStore, localCoordinator);

        WechatServiceCardRepairUnit localRepairUnit = mock(
                WechatServiceCardRepairUnit.class
        );
        when(runtime.captureEnabledFailSoft()).thenReturn(false);
        new WechatServiceCardRepairScheduler(
                jdbcClient, environmentProperties,
                () -> WechatServiceCardTestConfigs.fromProperties(environmentProperties),
                runtime, localRepairUnit,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).runOnce();
        verifyNoInteractions(localRepairUnit);
    }

    @Test
    void repairStopsBeforeTheNextCandidateWhenCaptureIsDisabledMidBatch() {
        long firstPaymentId = IDS.incrementAndGet();
        long firstOrderId = IDS.incrementAndGet();
        long secondPaymentId = IDS.incrementAndGet();
        long secondOrderId = IDS.incrementAndGet();
        LocalDateTime paidAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(1);
        seedPaidOrder(firstOrderId, firstPaymentId, paidAt);
        seedPaidOrder(secondOrderId, secondPaymentId, paidAt.plusMinutes(1));

        WechatServiceCardRuntimeSettingService runtime = mock(
                WechatServiceCardRuntimeSettingService.class
        );
        WechatServiceCardRepairUnit localRepairUnit = mock(
                WechatServiceCardRepairUnit.class
        );
        when(runtime.captureEnabledFailSoft())
                .thenReturn(true, true, false);
        WechatServiceCardRepairScheduler freshScheduler = new WechatServiceCardRepairScheduler(
                jdbcClient,
                readyProperties(),
                () -> WechatServiceCardTestConfigs.fromProperties(readyProperties()),
                runtime,
                localRepairUnit,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        AtomicLong cursor = (AtomicLong) ReflectionTestUtils.getField(
                freshScheduler, "paymentCursor"
        );
        assertThat(cursor).isNotNull();
        cursor.set(firstPaymentId - 1);

        freshScheduler.runOnce();

        verify(localRepairUnit).repair(
                firstOrderId, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
        verify(localRepairUnit, never()).repair(
                secondOrderId, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    private void seedPaidOrder(long orderId, long paymentId, LocalDateTime paidAt) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at,
                             created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'DIRECT', :idempotencyKey,
                             100, 100, :paidAt, :createdAt, :updatedAt)
                        """)
                .param("id", orderId)
                .param("orderNo", "RT-SCHED-" + orderId)
                .param("idempotencyKey", "runtime-scheduler-" + orderId)
                .param("paidAt", paidAt)
                .param("createdAt", paidAt)
                .param("updatedAt", paidAt)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, payer_openid,
                             status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :outTradeNo, :transactionId, :openid,
                             'PAID', 100, :expiresAt, :paidAt, :createdAt, :updatedAt)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", "RUNTIME-SCHEDULER-OUT-" + paymentId)
                .param("transactionId", "4200" + paymentId)
                .param("openid", "runtime-scheduler-openid-" + paymentId)
                .param("expiresAt", paidAt.plusMinutes(15))
                .param("paidAt", paidAt)
                .param("createdAt", paidAt)
                .param("updatedAt", paidAt)
                .update();
    }

    private WechatServiceCardProperties readyProperties() {
        return new WechatServiceCardProperties(
                false,
                false,
                "template-record",
                Duration.ofSeconds(15),
                50,
                Duration.ofMinutes(2),
                8,
                Duration.ofMinutes(1),
                Duration.ofMinutes(30),
                Duration.ofMinutes(1),
                Duration.ofHours(6),
                2,
                Duration.ofSeconds(3),
                Duration.ofSeconds(15),
                DataSize.ofMegabytes(1),
                DataSize.ofKilobytes(64),
                "https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png",
                false,
                List.of("admin.junxiangshiping.cn"),
                new WechatServiceCardProperties.Callback(
                        false, "", "", Duration.ofMinutes(5)
                )
        );
    }
}
