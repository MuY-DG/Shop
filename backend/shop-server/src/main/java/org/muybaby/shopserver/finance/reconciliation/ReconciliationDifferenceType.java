package org.muybaby.shopserver.finance.reconciliation;

public enum ReconciliationDifferenceType {
    CHANNEL_ONLY,
    LOCAL_ONLY,
    AMOUNT_MISMATCH,
    IDENTITY_MISMATCH,
    STATUS_MISMATCH,
    DUPLICATE_CHANNEL_ROW,
    SOURCE_CHANGED
}
