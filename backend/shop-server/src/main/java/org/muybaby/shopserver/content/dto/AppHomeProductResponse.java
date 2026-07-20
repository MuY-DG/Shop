package org.muybaby.shopserver.content.dto;

public record AppHomeProductResponse(
        Long placementId,
        Long spuId,
        String title,
        String subtitle,
        String imageUrl,
        AppHomeProductPriceResponse price,
        AppHomeProductBadgeResponse badge,
        java.util.List<AppHomeProductFeatureResponse> highlights,
        java.util.List<AppHomeProductFeatureResponse> metaFacts,
        AppHomeWholesaleSummaryResponse wholesaleSummary,
        Long displaySales,
        String path
) {
}
