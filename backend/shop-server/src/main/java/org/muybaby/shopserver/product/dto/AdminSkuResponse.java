package org.muybaby.shopserver.product.dto;

import java.math.BigDecimal;
import java.util.List;

public record AdminSkuResponse(
        Long id,
        String skuCode,
        String specJson,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Long costPriceCent,
        Integer stockAvailable,
        Integer lowStockThreshold,
        Integer weightGram,
        BigDecimal volumeCubicMeter,
        String image,
        Long imageFileId,
        String status,
        Boolean defaultSelected,
        String combinationKey,
        List<String> specValueKeys,
        List<WholesaleTierResponse> wholesaleTiers,
        Integer sortOrder
) {
}
