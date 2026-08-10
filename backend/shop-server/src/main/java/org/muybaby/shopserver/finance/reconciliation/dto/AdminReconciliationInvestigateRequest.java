package org.muybaby.shopserver.finance.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminReconciliationInvestigateRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 500) String reason
) {
}
