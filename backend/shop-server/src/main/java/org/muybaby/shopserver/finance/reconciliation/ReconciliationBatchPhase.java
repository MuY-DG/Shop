package org.muybaby.shopserver.finance.reconciliation;

public enum ReconciliationBatchPhase {
    QUEUED,
    DOWNLOAD,
    VERIFY,
    PARSE,
    STORE,
    COMPARE,
    COMPLETE
}
