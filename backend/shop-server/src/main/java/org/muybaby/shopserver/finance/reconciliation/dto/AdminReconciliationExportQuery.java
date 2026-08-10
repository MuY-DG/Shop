package org.muybaby.shopserver.finance.reconciliation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record AdminReconciliationExportQuery(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String mchId,
        String batchStatus,
        String differenceStatus,
        String differenceType
) {
}
