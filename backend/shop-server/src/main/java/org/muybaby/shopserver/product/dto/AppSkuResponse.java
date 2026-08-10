package org.muybaby.shopserver.product.dto;

import org.muybaby.shopserver.product.ProductSaleState;

import java.util.List;

public record AppSkuResponse(
        Long id,
        String skuCode,
        String specJson,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        ProductSaleState saleState,
        Integer maxPurchaseQuantity,
        Integer weightGram,
        String netContentText,
        String image,
        Long imageFileId,
        String status,
        List<WholesaleTierResponse> wholesaleTiers
) {
}
