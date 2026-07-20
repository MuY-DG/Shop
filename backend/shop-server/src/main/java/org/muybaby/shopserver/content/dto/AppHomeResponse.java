package org.muybaby.shopserver.content.dto;

import java.util.List;

public record AppHomeResponse(
        Integer schemaVersion,
        List<AppHomeBannerResponse> banners,
        List<AppHomeCategoryResponse> categories,
        List<AppHomeProductSectionResponse> productSections
) {
}
