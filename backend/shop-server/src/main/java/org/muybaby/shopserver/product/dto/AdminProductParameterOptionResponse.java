package org.muybaby.shopserver.product.dto;

public record AdminProductParameterOptionResponse(
        Long id,
        String optionCode,
        String optionLabel,
        Integer displayLevel,
        Integer sortOrder
) {
}
