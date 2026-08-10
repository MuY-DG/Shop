package org.muybaby.shopserver.finance.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminReconciliationResolveRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 64) String resolutionCode,
        @NotBlank @Size(max = 500) String reason
) {
}
