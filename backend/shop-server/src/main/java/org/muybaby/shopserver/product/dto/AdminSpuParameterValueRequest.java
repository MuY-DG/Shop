package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record AdminSpuParameterValueRequest(
        @NotNull Long parameterId,
        @Size(max = 500) String textValue,
        BigDecimal numberValue,
        Boolean booleanValue,
        List<String> optionCodes
) {
}
