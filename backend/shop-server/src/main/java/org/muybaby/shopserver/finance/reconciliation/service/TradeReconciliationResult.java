package org.muybaby.shopserver.finance.reconciliation.service;

import java.util.List;

public record TradeReconciliationResult(
        List<DifferenceDraft> differences,
        long localPaymentAmountCent,
        long localRefundAmountCent
) {
    public TradeReconciliationResult {
        differences = List.copyOf(differences);
    }
}
