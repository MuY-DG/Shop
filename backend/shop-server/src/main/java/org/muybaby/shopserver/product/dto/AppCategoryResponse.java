package org.muybaby.shopserver.product.dto;

public record AppCategoryResponse(
        Long id,
        Long parentId,
        String name,
        String icon,
        Long iconFileId,
        Integer sortOrder,
        String status
) {
}
