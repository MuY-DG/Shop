package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.WechatPaymentParamsResponse;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayRequest;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayResult;
import org.muybaby.shopserver.payment.service.PaymentInitiationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentInitiationServiceTest.PaymentInitiationProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentInitiationServiceTest extends PaymentTestSupport {

    @Autowired
    private PaymentInitiationService paymentInitiationService;

    @Autowired
    private TransactionProbeWechatPayProvider transactionProbeWechatPayProvider;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private PaymentConfigResolver paymentConfigResolver;

    @Test
    void paymentInheritsTheOrderDeadlineAndCannotExtendAnExpiredOrder() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-order-deadline-user");
        SeedOrder active = seedCreatedOrder(session.userId(), 6980L, false);
        LocalDateTime fixedDeadline = LocalDateTime.now().plusMinutes(30).withNano(0);
        jdbcClient.sql("""
                        update shop_order
                        set payment_expires_at = :deadline,
                            created_timeout_claim_token = 'superseded-created-claim',
                            created_timeout_claimed_at = timestampadd(MINUTE, -10, current_timestamp)
                        where id = :orderId
                        """)
                .param("deadline", fixedDeadline)
                .param("orderId", active.orderId())
                .update();

        paymentInitiationService.initiate(session.userId(), active.orderId());

        LocalDateTime paymentDeadline = jdbcClient.sql("""
                        select expires_at from payment_order where order_id = :orderId
                        """)
                .param("orderId", active.orderId())
                .query(LocalDateTime.class)
                .single();
        assertThat(paymentDeadline).isEqualTo(fixedDeadline);
        assertThat(jdbcClient.sql("""
                        select count(*) from shop_order
                        where id = :orderId
                          and created_timeout_claim_token is null
                          and created_timeout_claimed_at is null
                        """)
                .param("orderId", active.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);

        jdbcClient.sql("update shop_order set payment_expires_at = :deadline where id = :orderId")
                .param("deadline", fixedDeadline.plusMinutes(1))
                .param("orderId", active.orderId())
                .update();
        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), active.orderId()))
                .isInstanceOf(BusinessException.class);

        SeedOrder expired = seedCreatedOrder(session.userId(), 2100L, false);
        jdbcClient.sql("""
                        update shop_order
                        set payment_expires_at = timestampadd(SECOND, -1, current_timestamp)
                        where id = :orderId
                        """)
                .param("orderId", expired.orderId())
                .update();

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), expired.orderId()))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbcClient.sql("select count(*) from payment_order where order_id = :orderId")
                .param("orderId", expired.orderId())
                .query(Integer.class)
                .single()).isZero();
        assertThat(orderStatus(expired.orderId())).isEqualTo("CREATED");
    }

    @Test
    void providerRunsOutsideTransactionAndFailureRetriesTheExactPersistedRequest() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-retry-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        transactionProbeWechatPayProvider.failNextPrepay();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> paymentInitiationService.initiate(session.userId(), order.orderId())))
                .isInstanceOf(IllegalStateException.class);

        PaymentPreparationSnapshot failed = preparationSnapshot(order.orderId());
        assertThat(failed.status()).isEqualTo("PREPARING");
        assertThat(failed.paymentConfigId()).isEqualTo(91001L);
        assertThat(failed.payerOpenid()).isEqualTo(session.openid());
        assertThat(failed.amountCent()).isEqualTo(6980L);
        assertThat(failed.claimToken()).isNull();
        assertThat(failed.claimedAt()).isNull();
        assertThat(failed.prepayAttempts()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAYING");
        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringPrepay()).isFalse();

        switchToClonedPaymentConfig(91002L);
        WechatPaymentParamsResponse response = paymentInitiationService.initiate(session.userId(), order.orderId());

        PaymentPreparationSnapshot completed = preparationSnapshot(order.orderId());
        assertThat(completed.status()).isEqualTo("PAYING");
        assertThat(completed.paymentConfigId()).isEqualTo(91001L);
        assertThat(completed.prepayId()).isNotBlank();
        assertThat(completed.claimToken()).isNull();
        assertThat(completed.claimedAt()).isNull();
        assertThat(completed.prepayAttempts()).isEqualTo(2);
        assertThat(response.packageValue()).isEqualTo("prepay_id=" + completed.prepayId());
        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(2);
        assertThat(transactionProbeWechatPayProvider.requests().get(1))
                .isEqualTo(transactionProbeWechatPayProvider.requests().get(0));
        assertThat(transactionProbeWechatPayProvider.requests().getFirst().description())
                .isEqualTo("MuYbaby商城订单");
        assertThat(transactionProbeWechatPayProvider.configIds()).containsExactly(91001L, 91001L);
        assertThat(attemptCount(completed.outTradeNo(), "PREPAY_FAILED", false)).isEqualTo(1);
        assertThat(attemptCount(completed.outTradeNo(), "PREPAY_SUCCEEDED", true)).isEqualTo(1);

        WechatPaymentParamsResponse repeated = paymentInitiationService.initiate(session.userId(), order.orderId());
        assertThat(repeated.packageValue()).isEqualTo(response.packageValue());
        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(2);
    }

    @Test
    void legacyPaymentWithoutProviderDescriptionKeepsThePreviousBillText() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-legacy-description-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        transactionProbeWechatPayProvider.failNextPrepay();

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                .isInstanceOf(IllegalStateException.class);
        jdbcClient.sql("""
                        update payment_order
                        set provider_description = null
                        where order_id = :orderId
                        """)
                .param("orderId", order.orderId())
                .update();

        paymentInitiationService.initiate(session.userId(), order.orderId());

        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(2);
        assertThat(transactionProbeWechatPayProvider.requests().get(1).description())
                .isEqualTo("Shop order " + order.orderNo());
    }

    @Test
    void upgradeLegacyPayingPaymentReturnsItsExistingPrepayParameters() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-legacy-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        String outTradeNo = "P" + order.orderNo();
        String prepayId = "legacy-prepay-" + order.orderId();
        insertLegacyDigestPayingPayment(order, session.openid(), outTradeNo, prepayId, 6980L, 0);

        WechatPaymentParamsResponse response = paymentInitiationService.initiate(
                session.userId(), order.orderId());

        assertThat(response.packageValue()).isEqualTo("prepay_id=" + prepayId);
        assertThat(transactionProbeWechatPayProvider.requests()).isEmpty();
        assertThat(preparationSnapshot(order.orderId()).prepayAttempts()).isZero();
    }

    @Test
    void newPayingPaymentCannotUseLegacyDigestCompatibilityPath() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-new-digest-guard-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        String outTradeNo = "P" + order.orderNo();
        insertLegacyDigestPayingPayment(
                order,
                session.openid(),
                outTradeNo,
                "new-prepay-" + order.orderId(),
                6980L,
                1
        );

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                .isInstanceOf(BusinessException.class);
        assertThat(transactionProbeWechatPayProvider.requests()).isEmpty();
    }

    @Test
    void freshPreparingLeaseRejectsConcurrentInitiationWithoutSecondProviderCall() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-concurrent-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        transactionProbeWechatPayProvider.blockNextPrepay();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WechatPaymentParamsResponse> first = executor.submit(
                    () -> paymentInitiationService.initiate(session.userId(), order.orderId()));
            assertThat(transactionProbeWechatPayProvider.awaitBlockedPrepay()).isTrue();

            assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                    .isInstanceOf(BusinessException.class);
            assertThat(transactionProbeWechatPayProvider.requests()).hasSize(1);
            PaymentPreparationSnapshot preparing = preparationSnapshot(order.orderId());
            assertThat(preparing.status()).isEqualTo("PREPARING");
            assertThat(preparing.claimToken()).isNotBlank();
            assertThat(preparing.prepayAttempts()).isEqualTo(1);

            transactionProbeWechatPayProvider.releaseBlockedPrepay();
            assertThat(first.get(10, TimeUnit.SECONDS).packageValue()).startsWith("prepay_id=");
        } finally {
            transactionProbeWechatPayProvider.releaseBlockedPrepay();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(1);
        assertThat(preparationSnapshot(order.orderId()).status()).isEqualTo("PAYING");
        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringPrepay()).isFalse();
    }

    @Test
    void committedConfigUpdateBetweenResolveAndInsertIsRetriedWithTheLockedRevision() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-config-race-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        String replacementNotifyUrl = "https://pay.test/wxpay/pay/notify-v2";
        CountDownLatch firstResolutionCompleted = new CountDownLatch(1);
        CountDownLatch releaseFirstResolution = new CountDownLatch(1);
        AtomicBoolean blockFirstResolution = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ResolvedPaymentConfig resolved = (ResolvedPaymentConfig) invocation.callRealMethod();
            if (blockFirstResolution.compareAndSet(true, false)) {
                firstResolutionCompleted.countDown();
                if (!releaseFirstResolution.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Initial payment config resolution was not released");
                }
            }
            return resolved;
        }).when(paymentConfigResolver).resolve();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WechatPaymentParamsResponse> payment = executor.submit(
                    () -> paymentInitiationService.initiate(session.userId(), order.orderId()));
            assertThat(firstResolutionCompleted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(jdbcClient.sql("""
                            update payment_config
                            set notify_url = :notifyUrl,
                                updated_at = current_timestamp
                            where id = 91001
                              and not exists (
                                  select 1 from payment_order where payment_config_id = 91001
                              )
                            """)
                    .param("notifyUrl", replacementNotifyUrl)
                    .update()).isEqualTo(1);
            releaseFirstResolution.countDown();

            assertThat(payment.get(10, TimeUnit.SECONDS).packageValue()).startsWith("prepay_id=");
        } finally {
            releaseFirstResolution.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        String storedFingerprint = jdbcClient.sql("""
                        select payment_config_fingerprint
                        from payment_order
                        where order_id = :orderId
                        """)
                .param("orderId", order.orderId())
                .query(String.class)
                .single();
        ResolvedPaymentConfig storedConfig = paymentConfigResolver.resolveForPayment(
                91001L, storedFingerprint);

        assertThat(storedConfig.notifyUrl()).isEqualTo(replacementNotifyUrl);
        assertThat(transactionProbeWechatPayProvider.requests())
                .singleElement()
                .extracting(WechatJsapiPrepayRequest::notifyUrl)
                .isEqualTo(replacementNotifyUrl);
        assertThat(transactionProbeWechatPayProvider.configIds()).containsExactly(91001L);
    }

    @Test
    void expiredPreparingLeaseRecoversAfterCrashUsingIdenticalProviderRequest() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-crash-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        transactionProbeWechatPayProvider.crashAfterNextProviderSuccess();

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                .isInstanceOf(SimulatedProcessCrash.class);

        PaymentPreparationSnapshot orphaned = preparationSnapshot(order.orderId());
        assertThat(orphaned.status()).isEqualTo("PREPARING");
        assertThat(orphaned.claimToken()).isNotBlank();
        assertThat(orphaned.claimedAt()).isNotNull();
        assertThat(orphaned.prepayAttempts()).isEqualTo(1);
        assertThat(attemptCount(orphaned.outTradeNo(), "STARTED", false)).isEqualTo(1);

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                .isInstanceOf(BusinessException.class);
        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(1);

        jdbcClient.sql("""
                        update payment_order
                        set prepay_claimed_at = :expiredClaimedAt
                        where order_id = :orderId
                        """)
                .param("expiredClaimedAt", LocalDateTime.now().minusMinutes(10))
                .param("orderId", order.orderId())
                .update();

        WechatPaymentParamsResponse recovered = paymentInitiationService.initiate(
                session.userId(), order.orderId());

        PaymentPreparationSnapshot finalized = preparationSnapshot(order.orderId());
        assertThat(finalized.status()).isEqualTo("PAYING");
        assertThat(finalized.claimToken()).isNull();
        assertThat(finalized.claimedAt()).isNull();
        assertThat(finalized.prepayAttempts()).isEqualTo(2);
        assertThat(recovered.packageValue()).isEqualTo("prepay_id=" + finalized.prepayId());
        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(2);
        assertThat(transactionProbeWechatPayProvider.requests().get(1))
                .isEqualTo(transactionProbeWechatPayProvider.requests().get(0));
        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringPrepay()).isFalse();
    }

    @Test
    void expiredOrderDeadlinePreventsReclaimingAnOrphanedPreparingPayment() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-initiation-expired-orphan-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        transactionProbeWechatPayProvider.crashAfterNextProviderSuccess();

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                .isInstanceOf(SimulatedProcessCrash.class);
        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(1);

        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(1).withNano(0);
        jdbcClient.sql("""
                        update payment_order
                        set expires_at = :expiredAt,
                            prepay_claimed_at = timestampadd(MINUTE, -10, current_timestamp)
                        where order_id = :orderId
                        """)
                .param("expiredAt", expiredAt)
                .param("orderId", order.orderId())
                .update();
        jdbcClient.sql("""
                        update shop_order
                        set payment_expires_at = :expiredAt
                        where id = :orderId
                        """)
                .param("expiredAt", expiredAt)
                .param("orderId", order.orderId())
                .update();

        assertThatThrownBy(() -> paymentInitiationService.initiate(session.userId(), order.orderId()))
                .isInstanceOf(BusinessException.class);
        assertThat(transactionProbeWechatPayProvider.requests()).hasSize(1);
        assertThat(preparationSnapshot(order.orderId()).prepayAttempts()).isEqualTo(1);
    }

    private PaymentPreparationSnapshot preparationSnapshot(long orderId) {
        return jdbcClient.sql("""
                        select payment_config_id,
                               out_trade_no,
                               prepay_id,
                               payer_openid,
                               status,
                               amount_cent,
                               prepay_claim_token,
                               prepay_claimed_at,
                               prepay_attempts
                        from payment_order
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentPreparationSnapshot(
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("out_trade_no"),
                        rs.getString("prepay_id"),
                        rs.getString("payer_openid"),
                        rs.getString("status"),
                        rs.getLong("amount_cent"),
                        rs.getString("prepay_claim_token"),
                        rs.getObject("prepay_claimed_at", LocalDateTime.class),
                        rs.getInt("prepay_attempts")
                ))
                .single();
    }

    private void insertLegacyDigestPayingPayment(
            SeedOrder order,
            String payerOpenid,
            String outTradeNo,
            String prepayId,
            long amountCent,
            int prepayAttempts
    ) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbcClient.sql("""
                        update shop_order
                        set status = 'PAYING',
                            merchant_trade_no = :outTradeNo,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = 'CREATED'
                        """)
                .param("outTradeNo", outTradeNo)
                .param("updatedAt", now)
                .param("orderId", order.orderId())
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, out_trade_no, prepay_id, payer_openid, status,
                             amount_cent, currency, request_digest, expires_at, prepay_attempts,
                             created_at, updated_at)
                        values
                            (:orderId, 91001, :outTradeNo, :prepayId, :payerOpenid, 'PAYING',
                             :amountCent, 'CNY', :requestDigest, :expiresAt, :prepayAttempts,
                             :createdAt, :updatedAt)
                        """)
                .param("orderId", order.orderId())
                .param("outTradeNo", outTradeNo)
                .param("prepayId", prepayId)
                .param("payerOpenid", payerOpenid)
                .param("amountCent", amountCent)
                .param("requestDigest", legacyRequestDigest(outTradeNo, amountCent, payerOpenid))
                .param("expiresAt", now.plusMinutes(10))
                .param("prepayAttempts", prepayAttempts)
                .param("createdAt", now.minusMinutes(1))
                .param("updatedAt", now)
                .update();
    }

    private String legacyRequestDigest(String outTradeNo, long amountCent, String payerOpenid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String source = outTradeNo + "|" + amountCent + "|" + payerOpenid;
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String orderStatus(long orderId) {
        return jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private int attemptCount(String outTradeNo, String status, boolean bound) {
        return jdbcClient.sql("""
                        select count(*)
                        from payment_attempt
                        where out_trade_no = :outTradeNo
                          and status = :status
                          and (:bound = false or payment_order_id is not null)
                        """)
                .param("outTradeNo", outTradeNo)
                .param("status", status)
                .param("bound", bound)
                .query(Integer.class)
                .single();
    }

    private record PaymentPreparationSnapshot(
            Long paymentConfigId,
            String outTradeNo,
            String prepayId,
            String payerOpenid,
            String status,
            long amountCent,
            String claimToken,
            LocalDateTime claimedAt,
            int prepayAttempts
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PaymentInitiationProbeConfiguration {

        @Bean
        @Primary
        TransactionProbeWechatPayProvider transactionProbeWechatPayProvider(ObjectMapper objectMapper) {
            return new TransactionProbeWechatPayProvider(objectMapper);
        }
    }

    static final class TransactionProbeWechatPayProvider extends MockWechatPayProvider {

        private final List<WechatJsapiPrepayRequest> requests = new CopyOnWriteArrayList<>();
        private final List<Long> configIds = new CopyOnWriteArrayList<>();
        private final AtomicBoolean transactionObservedDuringPrepay = new AtomicBoolean();
        private final AtomicBoolean failNext = new AtomicBoolean();
        private final AtomicBoolean crashNext = new AtomicBoolean();
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private volatile CountDownLatch blockedPrepayEntered = new CountDownLatch(0);
        private volatile CountDownLatch blockedPrepayRelease = new CountDownLatch(0);

        TransactionProbeWechatPayProvider(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public WechatJsapiPrepayResult createJsapiPrepay(
                ResolvedPaymentConfig config,
                WechatJsapiPrepayRequest request
        ) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionObservedDuringPrepay.set(true);
            }
            requests.add(request);
            configIds.add(config.configId());
            if (blockNext.compareAndSet(true, false)) {
                blockedPrepayEntered.countDown();
                try {
                    if (!blockedPrepayRelease.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Blocked prepay was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Blocked prepay was interrupted", ex);
                }
            }
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("Synthetic prepay failure");
            }
            WechatJsapiPrepayResult result = super.createJsapiPrepay(config, request);
            if (crashNext.compareAndSet(true, false)) {
                throw new SimulatedProcessCrash();
            }
            return result;
        }

        void failNextPrepay() {
            failNext.set(true);
        }

        void crashAfterNextProviderSuccess() {
            crashNext.set(true);
        }

        void blockNextPrepay() {
            blockedPrepayEntered = new CountDownLatch(1);
            blockedPrepayRelease = new CountDownLatch(1);
            blockNext.set(true);
        }

        boolean awaitBlockedPrepay() throws InterruptedException {
            return blockedPrepayEntered.await(10, TimeUnit.SECONDS);
        }

        void releaseBlockedPrepay() {
            blockedPrepayRelease.countDown();
        }

        boolean transactionObservedDuringPrepay() {
            return transactionObservedDuringPrepay.get();
        }

        List<WechatJsapiPrepayRequest> requests() {
            return List.copyOf(requests);
        }

        List<Long> configIds() {
            return List.copyOf(configIds);
        }

        @Override
        public void reset() {
            releaseBlockedPrepay();
            super.reset();
            requests.clear();
            configIds.clear();
            transactionObservedDuringPrepay.set(false);
            failNext.set(false);
            crashNext.set(false);
            blockNext.set(false);
            blockedPrepayEntered = new CountDownLatch(0);
            blockedPrepayRelease = new CountDownLatch(0);
        }
    }

    private static final class SimulatedProcessCrash extends Error {
    }
}
