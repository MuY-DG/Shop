package org.muybaby.shopserver.product.dto;

public record AdminSpecTemplateValueResponse(
        Long id,
        String valueKey,
        String valueName,
        Integer sortOrder
) {
}
