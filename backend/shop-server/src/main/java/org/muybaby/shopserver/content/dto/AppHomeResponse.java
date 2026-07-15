package org.muybaby.shopserver.content.dto;

import java.util.List;

public record AppHomeResponse(
        List<AppHomeBannerResponse> banners,
        List<AppHomeCategoryResponse> categories,
        List<AppHomeProductResponse> hotProducts,
        List<AppHomeProductResponse> recommendedProducts
) {
}
