package org.muybaby.shopserver.finance.reconciliation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminFinanceReconciliationRuntimeUpdateRequest(
        @NotNull Boolean workerEnabled,
        @NotNull Boolean dailyEnabled,
        @NotNull @Min(0) Long version,
        @NotBlank @Size(min = 2, max = 200) String reason
) {
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown finance runtime setting field: " + name);
    }
}
