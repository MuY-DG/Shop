package org.muybaby.shopserver.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminWechatShippingRuntimeUpdateRequest(
        @NotNull Boolean uploadEnabled,
        @NotNull Boolean deliveryEnabled,
        @NotNull Boolean receiptReconciliationEnabled,
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(min = 2, max = 200) String reason
) {
}
