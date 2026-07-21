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
        Long salesCount,
        List<String> sellingPoints,
        String detailHtml,
        List<ProductImageResponse> images,
        List<AppSkuResponse> skus,
        List<AppProductParameterValueResponse> parameters,
        AppFreightTemplateResponse freightTemplate,
        List<AppGuaranteeServiceResponse> guaranteeServices,
        AppProductReviewSummaryResponse reviewSummary
) {
}
