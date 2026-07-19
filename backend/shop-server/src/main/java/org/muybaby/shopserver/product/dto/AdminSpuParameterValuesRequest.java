package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AdminSpuParameterValuesRequest(
        @NotNull @Valid List<AdminSpuParameterValueRequest> values
) {
}
