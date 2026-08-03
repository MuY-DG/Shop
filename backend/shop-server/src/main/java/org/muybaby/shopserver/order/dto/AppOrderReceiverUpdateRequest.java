package org.muybaby.shopserver.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AppOrderReceiverUpdateRequest(
        @NotNull @Positive Long addressId
) {
}
