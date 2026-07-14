package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import org.muybaby.shopserver.product.FreightChargeMode;
import org.muybaby.shopserver.product.FreightTemplateStatus;

public record AdminFreightTemplateRequest(
        @NotBlank @Size(max = 64) String name,
        @NotNull FreightChargeMode chargeMode,
        Long fixedAmountCent,
        @NotNull FreightTemplateStatus status,
        @Min(0) Integer sortOrder
) {
}
