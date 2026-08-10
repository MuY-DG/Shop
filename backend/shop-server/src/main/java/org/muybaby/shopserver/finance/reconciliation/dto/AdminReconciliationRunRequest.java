package org.muybaby.shopserver.finance.reconciliation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record AdminReconciliationRunRequest(
        @NotNull LocalDate billDate,
        @Size(max = 32) String mchId
) {
}
