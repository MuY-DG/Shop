package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductFoodDisclosureRequest(
        @NotBlank @Size(max = 20) String complianceType,
        @Size(max = 160) String foodName,
        @Size(max = 10000) String ingredients,
        @Size(max = 1000) String allergenInformation,
        @Size(max = 500) String storageConditions,
        @Size(max = 255) String shelfLifeDescription,
        @Size(max = 160) String manufacturerName,
        @Size(max = 512) String manufacturerAddress,
        @Size(max = 96) String productionLicenseNumber,
        @Size(max = 160) String origin,
        @Size(max = 1000) String consumerNotice,
        @Size(max = 500) String variableProductionNotice,
        @Valid @Size(max = 12) List<ProductFoodLabelAssetRequest> labelAssets
) {
}
