package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.service.PaymentCancellationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentCancellationServiceTest.PaymentCancellationProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentCancellationServiceTest extends PaymentTestSupport {

    @Autowired
    private PaymentCancellationService paymentCancellationService;

    @Autowired
    private TransactionProbeWechatPayProvider transactionProbeWechatPayProvider;

    @Test
    void providerCloseRunsOutsideTransactionAndLocalStateFinalizesAtomically() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-cancel-transaction-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        switchToClonedPaymentConfig(91002L);

        assertThat(paymentCancellationService.cancel(session.userId(), order.orderId()).status())
                .isEqualTo("CLOSED");

        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringClose()).isFalse();
        assertThat(transactionProbeWechatPayProvider.configIdObservedDuringClose()).isEqualTo(91001L);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(statusOf("payment_order", "out_trade_no", outTradeNo)).isEqualTo("CLOSED");
        assertThat(statusOf("shop_order", "id", order.orderId())).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_order
                        where out_trade_no = :outTradeNo
                          and timeout_close_claim_token is null
                          and timeout_close_claimed_at is null
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void providerFailureReleasesClaimAndLeavesOrderRetryable() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-cancel-failure-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, false);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        mockWechatPayProvider.failCloseFor(outTradeNo);

        assertThatThrownBy(() -> paymentCancellationService.cancel(session.userId(), order.orderId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(transactionProbeWechatPayProvider.transactionObservedDuringClose()).isFalse();
        assertThat(statusOf("payment_order", "out_trade_no", outTradeNo)).isEqualTo("PAYING");
        assertThat(statusOf("shop_order", "id", order.orderId())).isEqualTo("PAYING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_order
                        where out_trade_no = :outTradeNo
                          and timeout_close_claim_token is null
                          and timeout_close_claimed_at is null
                          and last_error_code = 'IllegalStateException'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void paidProviderResultWinsOverCancellation() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-cancel-paid-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        mockWechatPayProvider.markOrderPaid(outTradeNo, 6980L, "wx-cancel-paid");

        assertThatThrownBy(() -> paymentCancellationService.cancel(session.userId(), order.orderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));

        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).isEmpty();
        assertThat(statusOf("payment_order", "out_trade_no", outTradeNo)).isEqualTo("PAID");
        assertThat(statusOf("shop_order", "id", order.orderId())).isEqualTo("PAID");
    }

    @Test
    void alreadyClosedProviderOrderCompletesLocalCancellationWithoutSecondClose() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-cancel-provider-closed-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 3100L, false);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
        mockWechatPayProvider.markOrderState(outTradeNo, "CLOSED");

        assertThat(paymentCancellationService.cancel(session.userId(), order.orderId()).status())
                .isEqualTo("CLOSED");

        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).isEmpty();
        assertThat(statusOf("payment_order", "out_trade_no", outTradeNo)).isEqualTo("CLOSED");
        assertThat(statusOf("shop_order", "id", order.orderId())).isEqualTo("CLOSED");
    }

    @Test
    void releasedPreparingPaymentCanBeCancelled() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-cancel-preparing-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 3200L, false);
        pay(session.token(), order.orderId());
        String outTradeNo = activeOutTradeNo(order.orderId());
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

        assertThat(paymentCancellationService.cancel(session.userId(), order.orderId()).status())
                .isEqualTo("CLOSED");

        assertThat(mockWechatPayProvider.queriedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(statusOf("payment_order", "out_trade_no", outTradeNo)).isEqualTo("CLOSED");
    }

    private String statusOf(String tableName, String keyColumn, Object keyValue) {
        boolean supported = ("payment_order".equals(tableName) && "out_trade_no".equals(keyColumn))
                || ("shop_order".equals(tableName) && "id".equals(keyColumn));
        if (!supported) {
            throw new IllegalArgumentException("Unsupported payment status lookup");
        }
        return jdbcClient.sql("select status from %s where %s = :keyValue".formatted(tableName, keyColumn))
                .param("keyValue", keyValue)
                .query(String.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PaymentCancellationProbeConfiguration {

        @Bean
        @Primary
        TransactionProbeWechatPayProvider transactionProbeWechatPayProvider(ObjectMapper objectMapper) {
            return new TransactionProbeWechatPayProvider(objectMapper);
        }
    }

    static final class TransactionProbeWechatPayProvider extends MockWechatPayProvider {

        private final AtomicBoolean transactionObservedDuringClose = new AtomicBoolean();
        private final AtomicReference<Long> configIdObservedDuringClose = new AtomicReference<>();

        TransactionProbeWechatPayProvider(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public WechatPayOrderQueryResult queryOrder(ResolvedPaymentConfig config, String outTradeNo) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionObservedDuringClose.set(true);
            }
            configIdObservedDuringClose.set(config.configId());
            return super.queryOrder(config, outTradeNo);
        }

        @Override
        public void closeOrder(ResolvedPaymentConfig config, String outTradeNo) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionObservedDuringClose.set(true);
            }
            configIdObservedDuringClose.set(config.configId());
            super.closeOrder(config, outTradeNo);
        }

        boolean transactionObservedDuringClose() {
            return transactionObservedDuringClose.get();
        }

        Long configIdObservedDuringClose() {
            return configIdObservedDuringClose.get();
        }

        @Override
        public void reset() {
            super.reset();
            transactionObservedDuringClose.set(false);
            configIdObservedDuringClose.set(null);
        }
    }
}
