package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AdminCategoryResponse(
        Long id,
        Long parentId,
        String name,
        String icon,
        Integer sortOrder,
        String status,
        List<AdminCategoryResponse> children
) {
}
