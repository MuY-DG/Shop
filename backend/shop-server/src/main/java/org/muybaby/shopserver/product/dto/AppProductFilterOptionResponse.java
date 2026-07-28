package org.muybaby.shopserver.product.dto;

public record AppProductFilterOptionResponse(
        String optionCode,
        String optionLabel,
        Integer displayLevel,
        Long productCount
) {
}
