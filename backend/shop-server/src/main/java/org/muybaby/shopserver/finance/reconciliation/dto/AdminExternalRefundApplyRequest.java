package org.muybaby.shopserver.finance.reconciliation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminExternalRefundApplyRequest(
        @Min(0) long version,
        @NotBlank @Size(max = 500) String reason
) {
}
