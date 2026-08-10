package org.muybaby.shopserver.product.dto;

public record ProductFoodLabelAssetResponse(
        Long fileId,
        String url,
        Integer sortOrder
) {
}
