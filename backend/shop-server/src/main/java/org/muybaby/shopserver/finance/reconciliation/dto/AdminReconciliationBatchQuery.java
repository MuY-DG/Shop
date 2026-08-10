package org.muybaby.shopserver.finance.reconciliation.dto;

import java.time.LocalDate;

public record AdminReconciliationBatchQuery(
        Long current,
        Long size,
        LocalDate billDateFrom,
        LocalDate billDateTo,
        String mchId,
        String status
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1L : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20L : Math.min(size, 100L);
    }
}
