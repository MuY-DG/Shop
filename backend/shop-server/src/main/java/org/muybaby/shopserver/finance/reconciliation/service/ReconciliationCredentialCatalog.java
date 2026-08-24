package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationCredentialCatalog {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationCredentialCatalog.class);

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;

    public ReconciliationCredentialCatalog(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
    }

    public List<ReconciliationCredential> available(LocalDate billDate) {
        Map<String, ReconciliationCredential> byMerchant = new LinkedHashMap<>();
        boolean currentUnavailable = false;
        try {
            ResolvedPaymentConfig current = paymentConfigResolver.resolve();
            if (current.enabled()) {
                byMerchant.put(current.mchId(), credential(current));
            }
        } catch (BusinessException ex) {
            currentUnavailable = true;
            log.warn("Current payment credential is unavailable for finance reconciliation");
        }
        for (CredentialIdentity identity : identitiesForDate(billDate)) {
            try {
                ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                        identity.configId(), identity.fingerprint());
                byMerchant.putIfAbsent(config.mchId(), credential(config));
            } catch (BusinessException ex) {
                log.warn(
                        "Historical payment credential cannot be resolved for finance reconciliation: configIdPresent={}",
                        identity.configId() != null);
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_UNAVAILABLE);
            }
        }
        if (byMerchant.isEmpty() && currentUnavailable) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_UNAVAILABLE);
        }
        return List.copyOf(byMerchant.values());
    }

    public ReconciliationCredential require(String mchId, LocalDate billDate) {
        return available(billDate).stream()
                .filter(candidate -> candidate.mchId().equals(mchId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No usable payment credential exists for reconciliation merchant"));
    }

    private List<CredentialIdentity> identitiesForDate(LocalDate billDate) {
        LocalDateTime start = TimePolicy.businessDayStartUtc(billDate);
        LocalDateTime end = TimePolicy.businessDayStartUtc(billDate.plusDays(1));
        return jdbcClient.sql("""
                        select distinct payment.payment_config_id, payment.payment_config_fingerprint
                        from payment_order payment
                        where (
                                payment.status = 'PAID'
                                and payment.paid_at >= :startAt
                                and payment.paid_at < :endAt
                            )
                           or (
                                payment.status in ('PREPARING', 'PAYING', 'CLOSED')
                                and payment.created_at < :endAt
                                and payment.expires_at >= :startAt
                            )
                           or exists (
                                select 1
                                from refund_order refund
                                where refund.payment_order_id = payment.id
                                  and refund.requested_at >= :startAt
                                  and refund.requested_at < :endAt
                            )
                        order by payment.payment_config_id, payment.payment_config_fingerprint
                        """)
                .param("startAt", start)
                .param("endAt", end)
                .query((rs, rowNum) -> new CredentialIdentity(
                        rs.getLong("payment_config_id"),
                        rs.getString("payment_config_fingerprint")
                ))
                .list();
    }

    private ReconciliationCredential credential(ResolvedPaymentConfig config) {
        return new ReconciliationCredential(
                config.mchId(),
                config.configId(),
                paymentConfigResolver.fingerprint(config),
                config
        );
    }

    private record CredentialIdentity(Long configId, String fingerprint) {
    }
}
