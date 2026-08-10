package org.muybaby.shopserver.finance.reconciliation;

public enum ReconciliationBatchStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    BALANCED,
    DIFFERENCES,
    EMPTY,
    FAILED
}
