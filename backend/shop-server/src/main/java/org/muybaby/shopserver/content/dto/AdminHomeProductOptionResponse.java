package org.muybaby.shopserver.content.dto;

public record AdminHomeProductOptionResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        Long minPriceCent,
        Long maxPriceCent
) {
}
