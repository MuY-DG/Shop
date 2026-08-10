package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceSeverity;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceType;

public record DifferenceDraft(
        String diffKey,
        ReconciliationDifferenceType type,
        ReconciliationDifferenceSeverity severity,
        String transactionId,
        String outTradeNo,
        String refundId,
        String outRefundNo,
        Long orderId,
        Long paymentOrderId,
        Long refundOrderId,
        Long providerAmountCent,
        Long localAmountCent,
        String providerStatus,
        String localStatus,
        String providerEvidence,
        String localEvidence,
        String candidateContentSha256,
        String candidateStorageProvider,
        String candidateStorageContainer,
        String candidateStorageRegion,
        String candidateObjectKey,
        Long candidateSizeBytes
) {
    public DifferenceDraft(
            String diffKey,
            ReconciliationDifferenceType type,
            ReconciliationDifferenceSeverity severity,
            String transactionId,
            String outTradeNo,
            String refundId,
            String outRefundNo,
            Long orderId,
            Long paymentOrderId,
            Long refundOrderId,
            Long providerAmountCent,
            Long localAmountCent,
            String providerStatus,
            String localStatus,
            String providerEvidence,
            String localEvidence
    ) {
        this(
                diffKey, type, severity, transactionId, outTradeNo, refundId, outRefundNo,
                orderId, paymentOrderId, refundOrderId, providerAmountCent, localAmountCent,
                providerStatus, localStatus, providerEvidence, localEvidence,
                "", "", "", "", "", null
        );
    }
}
