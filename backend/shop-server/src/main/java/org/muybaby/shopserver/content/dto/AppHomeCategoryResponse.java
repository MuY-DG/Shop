package org.muybaby.shopserver.content.dto;

public record AppHomeCategoryResponse(
        Long id,
        Long categoryId,
        String name,
        String imageUrl,
        String path
) {
}
