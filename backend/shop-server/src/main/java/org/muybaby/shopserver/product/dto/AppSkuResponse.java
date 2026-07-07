package org.muybaby.shopserver.product.dto;

public record AppSkuResponse(
        Long id,
        String skuCode,
        String specJson,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Integer stockAvailable,
        Integer weightGram,
        String image,
        String status
) {
}
