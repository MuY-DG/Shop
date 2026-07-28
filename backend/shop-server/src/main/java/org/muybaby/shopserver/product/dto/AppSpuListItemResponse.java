package org.muybaby.shopserver.product.dto;

import org.muybaby.shopserver.product.ProductSaleState;

import java.util.List;

public record AppSpuListItemResponse(
        Long id,
        Long categoryId,
        String title,
        String subtitle,
        String mainImage,
        List<String> sellingPoints,
        Long minPriceCent,
        Long maxPriceCent,
        Long displaySales,
        ProductSaleState saleState,
        String badgeText,
        String badgeTone,
        List<AppProductParameterValueResponse> parameters
) {
}
