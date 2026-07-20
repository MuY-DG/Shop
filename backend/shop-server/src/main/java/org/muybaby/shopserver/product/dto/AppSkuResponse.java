package org.muybaby.shopserver.product.dto;

import java.util.List;

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
        Long imageFileId,
        String status,
        List<WholesaleTierResponse> wholesaleTiers
) {
}
