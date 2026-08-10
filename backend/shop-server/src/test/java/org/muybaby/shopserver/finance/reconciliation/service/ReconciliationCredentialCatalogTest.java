package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReconciliationCredentialCatalogTest {

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 8, 1);
    private static final String FINGERPRINT = "9".repeat(64);

    @Autowired
    private ReconciliationCredentialCatalog catalog;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PaymentConfigResolver paymentConfigResolver;

    @Test
    void unavailableCurrentConfigDoesNotBlockUsableHistoricalIdentity() {
        insertPaidEnvironmentPayment(9_362_001L, 9_362_002L);
        ResolvedPaymentConfig historical = mock(ResolvedPaymentConfig.class);
        when(paymentConfigResolver.resolve())
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        when(paymentConfigResolver.resolveForPayment(isNull(), org.mockito.ArgumentMatchers.eq(FINGERPRINT)))
                .thenReturn(historical);
        when(historical.mchId()).thenReturn("mch-historical");
        when(historical.configId()).thenReturn(null);
        when(paymentConfigResolver.fingerprint(historical)).thenReturn(FINGERPRINT);

        assertThat(catalog.available(BILL_DATE))
                .singleElement()
                .satisfies(credential -> {
                    assertThat(credential.mchId()).isEqualTo("mch-historical");
                    assertThat(credential.fingerprint()).isEqualTo(FINGERPRINT);
                });
    }

    @Test
    void unresolvedHistoricalIdentityFailsClosedInsteadOfSilentlySkippingMerchant() {
        insertPaidEnvironmentPayment(9_362_003L, 9_362_004L);
        when(paymentConfigResolver.resolve())
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        when(paymentConfigResolver.resolveForPayment(isNull(), org.mockito.ArgumentMatchers.eq(FINGERPRINT)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> catalog.available(BILL_DATE))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.FINANCE_RECONCILIATION_UNAVAILABLE));
    }

    @Test
    void currentMerchantSwitchStillDiscoversOldPayingIdentityWithMissedCallback() {
        insertEnvironmentPayment(9_362_005L, 9_362_006L, "PAYING", null);
        ResolvedPaymentConfig current = mock(ResolvedPaymentConfig.class);
        ResolvedPaymentConfig historical = mock(ResolvedPaymentConfig.class);
        when(paymentConfigResolver.resolve()).thenReturn(current);
        when(current.enabled()).thenReturn(true);
        when(current.mchId()).thenReturn("mch-current-new");
        when(current.configId()).thenReturn(77L);
        when(paymentConfigResolver.fingerprint(current)).thenReturn("8".repeat(64));
        when(paymentConfigResolver.resolveForPayment(isNull(), org.mockito.ArgumentMatchers.eq(FINGERPRINT)))
                .thenReturn(historical);
        when(historical.mchId()).thenReturn("mch-historical-missed-callback");
        when(historical.configId()).thenReturn(null);
        when(paymentConfigResolver.fingerprint(historical)).thenReturn(FINGERPRINT);

        assertThat(catalog.available(BILL_DATE))
                .extracting(ReconciliationCredential::mchId)
                .containsExactly("mch-current-new", "mch-historical-missed-callback");
    }

    private void insertPaidEnvironmentPayment(long orderId, long paymentId) {
        insertEnvironmentPayment(
                orderId,
                paymentId,
                "PAID",
                LocalDateTime.of(2026, 8, 1, 2, 0));
    }

    private void insertEnvironmentPayment(
            long orderId,
            long paymentId,
            String status,
            LocalDateTime paidAt
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 2, 0);
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             paid_amount_cent, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'CART', :idempotencyKey,
                             100, :now, :now)
                        """)
                .param("id", orderId)
                .param("orderNo", "ORDER-CREDENTIAL-" + orderId)
                .param("idempotencyKey", "credential-" + orderId)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, payment_config_fingerprint,
                             out_trade_no, transaction_id, status, amount_cent, expires_at,
                             paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, null, :fingerprint,
                             :outTradeNo, :transactionId, :status, 100, :expiresAt,
                             :paidAt, :createdAt, :updatedAt)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("fingerprint", FINGERPRINT)
                .param("outTradeNo", "TRADE-CREDENTIAL-" + paymentId)
                .param("transactionId", "TX-CREDENTIAL-" + paymentId)
                .param("status", status)
                .param("expiresAt", now.plusHours(1))
                .param("paidAt", paidAt)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }
}
