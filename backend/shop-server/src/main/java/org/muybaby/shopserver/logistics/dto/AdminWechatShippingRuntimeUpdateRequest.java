package org.muybaby.shopserver.logistics.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AdminWechatShippingRuntimeUpdateRequest(
        @NotNull Boolean uploadEnabled,
        @NotNull Boolean deliveryEnabled,
        @NotNull Boolean receiptReconciliationEnabled,
        @NotNull @PositiveOrZero Long version
) {
}
