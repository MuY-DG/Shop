package org.muybaby.shopserver.finance.reconciliation.dto;

public record AdminReconciliationEntryQuery(
        Long current,
        Long size,
        String entryType,
        String keyword
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1L : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20L : Math.min(size, 100L);
    }
}
