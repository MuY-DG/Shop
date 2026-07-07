package org.muybaby.shopserver.product.dto;

public record AdminSkuResponse(
        Long id,
        String skuCode,
        String specJson,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Integer stockAvailable,
        Integer weightGram,
        String image,
        String status,
        Integer sortOrder
) {
}
