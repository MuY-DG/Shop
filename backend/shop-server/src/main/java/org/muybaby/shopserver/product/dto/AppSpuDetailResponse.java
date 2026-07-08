package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AppSpuDetailResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        Long mainImageFileId,
        List<String> sellingPoints,
        String detailHtml,
        List<ProductImageResponse> images,
        List<AppSkuResponse> skus
) {
}
