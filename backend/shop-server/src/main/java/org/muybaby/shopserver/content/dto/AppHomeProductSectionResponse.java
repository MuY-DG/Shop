package org.muybaby.shopserver.content.dto;

import java.util.List;

public record AppHomeProductSectionResponse(
        String code,
        String presentation,
        List<AppHomeProductResponse> products
) {
}
