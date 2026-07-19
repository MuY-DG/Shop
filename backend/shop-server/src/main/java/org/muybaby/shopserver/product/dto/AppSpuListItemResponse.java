package org.muybaby.shopserver.product.dto;

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
        Integer totalStock,
        List<AppProductParameterValueResponse> parameters
) {
}
