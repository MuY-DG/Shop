package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;

public record ReconciliationCredential(
        String mchId,
        Long configId,
        String fingerprint,
        ResolvedPaymentConfig config
) {
}
