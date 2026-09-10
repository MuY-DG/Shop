package org.muybaby.shopserver.product.dto;

import org.muybaby.shopserver.product.ProductSaleState;

public record ProductSearchMatchResponse(
        Long skuId,
        String specText,
        String skuCode,
        String status,
        ProductSaleState saleState
) {
}
