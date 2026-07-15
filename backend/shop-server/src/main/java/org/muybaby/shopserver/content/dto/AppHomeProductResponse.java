package org.muybaby.shopserver.content.dto;

public record AppHomeProductResponse(
        Long id,
        Long spuId,
        String title,
        String subtitle,
        String imageUrl,
        Long minPriceCent,
        Long maxPriceCent,
        String path
) {
}
