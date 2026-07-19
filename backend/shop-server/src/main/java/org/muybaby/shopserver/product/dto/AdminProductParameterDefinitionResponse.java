package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminProductParameterDefinitionResponse(
        Long id,
        String parameterCode,
        String parameterName,
        String valueType,
        String unit,
        String description,
        Boolean required,
        Boolean filterable,
        Boolean cardVisible,
        Boolean detailVisible,
        Integer sortOrder,
        String status,
        List<Long> categoryIds,
        List<AdminProductParameterOptionResponse> options,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
