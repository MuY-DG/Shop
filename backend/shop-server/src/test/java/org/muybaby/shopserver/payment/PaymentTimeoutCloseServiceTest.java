package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.service.PaymentTimeoutCloseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentTimeoutCloseServiceTest.PaymentTimeoutProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentTimeoutCloseServiceTest extends PaymentTestSupport {

    @Autowired
    private PaymentTimeoutCloseService paymentTimeoutCloseService;

    @Autowired
    private TransactionProbeWechatPayProvider transactionProbeWechatPayProvider;

    @Test
    void expiredPayingPaymentIsClosedThroughProviderAndReleasesOrderLocks() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        String outTradeNo = "PAYTIMEOUT" + order.orderId();
        insertExpiredPayingPayment(order, outTradeNo, session.openid(), 6980L);
        switchToClonedPaymentConfig(91002L);
        jdbcClient.sql("""
                        insert into payment_attempt
                            (order_id, out_trade_no, status, amount_cent, error_code, error_message,
                             started_at, created_at, updated_at)
                        values
                            (:orderId, :outTradeNo, 'PREPAY_FAILED', 6980, 'PROVIDER_ERROR', 'previous failure',
                             timestamp '2026-07-07 08:40:00', timestamp '2026-07-07 08:40:00',
                             timestamp '2026-07-07 08:40:01')
                        """)
                .param("orderId", order.orderId())
                .param("outTradeNo", outTradeNo)
                .update();

        int closedCount = paymentTimeoutCloseService.closeExpiredPayments();

        assertThat(closedCount).isEqualTo(1);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(transactionProbeWechatPayProvider.configIdFor(outTradeNo)).isEqualTo(91001L);
        assertThat(jdbcClient.sql("select status from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", "PAYTIMEOUT" + order.orderId())
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("RELEASED");
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", order.skuId())
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where id = :userCouponId
                          and status = 'CLAIMED'
                          and locked_order_id is null
                          and locked_at is null
                          and released_at is not null
                        """)
                .param("userCouponId", order.userCouponId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt a
                        join payment_order p on p.id = a.payment_order_id
                        where a.out_trade_no = :outTradeNo
                          and a.status = 'CLOSED'
                          and p.status = 'CLOSED'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt
                        where out_trade_no = :outTradeNo
                          and status = 'PREPAY_FAILED'
                          and payment_order_id is null
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(paymentTimeoutCloseService.closeExpiredPayments()).isZero();
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringClose()).isFalse();
    }

    @Test
    void expiredPaymentPaidAtProviderIsFinalizedInsteadOfClosed() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-paid-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        String outTradeNo = "PAYTIMEOUTPAID" + order.orderId();
        insertExpiredPayingPayment(order, outTradeNo, session.openid(), 6980L);
        mockWechatPayProvider.markOrderPaid(outTradeNo, 6980L, "wx-timeout-paid");

        assertThat(paymentTimeoutCloseService.closeExpiredPayments()).isZero();

        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).isEmpty();
        assertPaymentStatus(outTradeNo, "PAID");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CONFIRMED");
        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringClose()).isFalse();
    }

    @Test
    void providerClosedStateCompletesLocalCloseAfterEarlierFinalizeCrash() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-closed-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 2100L, false);
        String outTradeNo = "PAYTIMEOUTCLOSED" + order.orderId();
        insertExpiredPayingPayment(order, outTradeNo, session.openid(), 2100L);
        mockWechatPayProvider.markOrderState(outTradeNo, "CLOSED");

        assertThat(paymentTimeoutCloseService.closeExpiredPayments()).isEqualTo(1);

        assertPaymentStatus(outTradeNo, "CLOSED");
        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).isEmpty();
    }

    @Test
    void expiredPreparingPaymentWithReleasedPrepayLeaseIsReconciledAndClosed() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-preparing-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 2200L, false);
        String outTradeNo = "PAYTIMEOUTPREPARING" + order.orderId();
        insertExpiredPayingPayment(order, outTradeNo, session.openid(), 2200L);
        jdbcClient.sql("""
                        update payment_order
                        set status = 'PREPARING',
                            prepay_id = '',
                            prepay_claim_token = null,
                            prepay_claimed_at = null
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
                .update();

        assertThat(paymentTimeoutCloseService.closeExpiredPayments()).isEqualTo(1);

        assertPaymentStatus(outTradeNo, "CLOSED");
        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
    }

    @Test
    void freshPreparingLeaseIsNotTakenByTimeoutScanner() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-fresh-preparing-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 2300L, false);
        String outTradeNo = "PAYTIMEOUTFRESHPREP" + order.orderId();
        insertExpiredPayingPayment(order, outTradeNo, session.openid(), 2300L);
        jdbcClient.sql("""
                        update payment_order
                        set status = 'PREPARING',
                            prepay_id = '',
                            prepay_claim_token = 'fresh-prepay-lease',
                            prepay_claimed_at = current_timestamp
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
                .update();

        assertThat(paymentTimeoutCloseService.closeExpiredPayments()).isZero();
        assertPaymentStatus(outTradeNo, "PREPARING");
        assertThat(mockWechatPayProvider.queriedOutTradeNos()).isEmpty();
        assertThat(mockWechatPayProvider.closedOutTradeNos()).isEmpty();
    }

    @Test
    void batchIsBoundedAndOneProviderFailureDoesNotAbandonLaterRows() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-batch-user");
        SeedOrder first = seedCreatedOrder(session.userId(), 1100L, false);
        SeedOrder second = seedCreatedOrder(session.userId(), 1200L, false);
        SeedOrder third = seedCreatedOrder(session.userId(), 1300L, false);
        String firstTradeNo = "PAYTIMEOUTFAIL" + first.orderId();
        String secondTradeNo = "PAYTIMEOUTNEXT" + second.orderId();
        String thirdTradeNo = "PAYTIMEOUTREST" + third.orderId();
        insertExpiredPayingPayment(first, firstTradeNo, session.openid(), 1100L);
        insertExpiredPayingPayment(second, secondTradeNo, session.openid(), 1200L);
        insertExpiredPayingPayment(third, thirdTradeNo, session.openid(), 1300L);
        mockWechatPayProvider.failCloseFor(firstTradeNo);

        int closedCount = paymentTimeoutCloseService.closeExpiredPayments(2);

        assertThat(closedCount).isEqualTo(1);
        assertPaymentStatus(firstTradeNo, "PAYING");
        assertPaymentStatus(secondTradeNo, "CLOSED");
        assertPaymentStatus(thirdTradeNo, "PAYING");
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(secondTradeNo);
        assertThat(jdbcClient.sql("""
                        select timeout_close_attempts
                        from payment_order
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", firstTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_order
                        where out_trade_no = :outTradeNo
                          and timeout_close_claim_token is not null
                          and last_error_code = 'IllegalStateException'
                        """)
                .param("outTradeNo", firstTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(paymentTimeoutCloseService.closeExpiredPayments(2)).isEqualTo(1);
        assertPaymentStatus(thirdTradeNo, "CLOSED");
        assertPaymentStatus(firstTradeNo, "PAYING");
    }

    @Test
    void concurrentScansClaimDifferentRowsAndCloseEachPaymentOnce() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-concurrent-user");
        SeedOrder first = seedCreatedOrder(session.userId(), 2100L, false);
        SeedOrder second = seedCreatedOrder(session.userId(), 2200L, false);
        String firstTradeNo = "PAYTIMEOUTRACEA" + first.orderId();
        String secondTradeNo = "PAYTIMEOUTRACEB" + second.orderId();
        insertExpiredPayingPayment(first, firstTradeNo, session.openid(), 2100L);
        insertExpiredPayingPayment(second, secondTradeNo, session.openid(), 2200L);

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstScan = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return paymentTimeoutCloseService.closeExpiredPayments(1);
            });
            Future<Integer> secondScan = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return paymentTimeoutCloseService.closeExpiredPayments(1);
            });

            assertThat(firstScan.get(20, TimeUnit.SECONDS) + secondScan.get(20, TimeUnit.SECONDS))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertPaymentStatus(firstTradeNo, "CLOSED");
        assertPaymentStatus(secondTradeNo, "CLOSED");
        assertThat(mockWechatPayProvider.closedOutTradeNos())
                .containsExactlyInAnyOrder(firstTradeNo, secondTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).doesNotHaveDuplicates();
        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringClose()).isFalse();
    }

    private void assertPaymentStatus(String outTradeNo, String expectedStatus) {
        assertThat(jdbcClient.sql("""
                        select status
                        from payment_order
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", outTradeNo)
                .query(String.class)
                .single()).isEqualTo(expectedStatus);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PaymentTimeoutProbeConfiguration {

        @Bean
        @Primary
        TransactionProbeWechatPayProvider transactionProbeWechatPayProvider(ObjectMapper objectMapper) {
            return new TransactionProbeWechatPayProvider(objectMapper);
        }
    }

    static final class TransactionProbeWechatPayProvider extends MockWechatPayProvider {

        private final AtomicBoolean transactionObservedDuringClose = new AtomicBoolean();
        private final Map<String, Long> configIdsByTradeNo = new ConcurrentHashMap<>();

        TransactionProbeWechatPayProvider(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public WechatPayOrderQueryResult queryOrder(ResolvedPaymentConfig config, String outTradeNo) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionObservedDuringClose.set(true);
            }
            configIdsByTradeNo.put(outTradeNo, config.configId());
            return super.queryOrder(config, outTradeNo);
        }

        @Override
        public void closeOrder(ResolvedPaymentConfig config, String outTradeNo) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionObservedDuringClose.set(true);
            }
            configIdsByTradeNo.put(outTradeNo, config.configId());
            super.closeOrder(config, outTradeNo);
        }

        boolean transactionObservedDuringClose() {
            return transactionObservedDuringClose.get();
        }

        Long configIdFor(String outTradeNo) {
            return configIdsByTradeNo.get(outTradeNo);
        }

        @Override
        public void reset() {
            super.reset();
            transactionObservedDuringClose.set(false);
            configIdsByTradeNo.clear();
        }
    }
}
