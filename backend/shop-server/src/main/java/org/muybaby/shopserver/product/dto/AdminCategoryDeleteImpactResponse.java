package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AdminCategoryDeleteImpactResponse(
        Long categoryId,
        String categoryName,
        boolean deletable,
        List<AdminCategoryDeleteBlockerResponse> blockers
) {
}
