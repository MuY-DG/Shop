package org.muybaby.shopserver.product.dto;

public record AppProductParameterOptionValueResponse(
        String optionCode,
        String optionLabel,
        Integer displayLevel
) {
}
