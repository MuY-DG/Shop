package org.muybaby.shopserver.product.dto;

import org.muybaby.shopserver.product.ProductSaleState;

import java.util.List;

public record AppSpuDetailResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String specType,
        String title,
        String subtitle,
        String mainImage,
        Long mainImageFileId,
        Long salesCount,
        ProductSaleState saleState,
        List<String> sellingPoints,
        ProductFoodDisclosureResponse foodDisclosure,
        String detailHtml,
        List<ProductImageResponse> images,
        List<AppSkuResponse> skus,
        List<AppProductParameterValueResponse> parameters,
        AppFreightTemplateResponse freightTemplate,
        List<AppGuaranteeServiceResponse> guaranteeServices,
        AppProductReviewSummaryResponse reviewSummary
) {
}
