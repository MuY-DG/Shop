package org.muybaby.shopserver.product.dto;

import java.util.List;

public record ProductFoodDisclosureResponse(
        String complianceType,
        String foodName,
        String ingredients,
        String allergenInformation,
        String storageConditions,
        String shelfLifeDescription,
        String manufacturerName,
        String manufacturerAddress,
        String productionLicenseNumber,
        String origin,
        String consumerNotice,
        String variableProductionNotice,
        List<ProductFoodLabelAssetResponse> labelAssets
) {
}
