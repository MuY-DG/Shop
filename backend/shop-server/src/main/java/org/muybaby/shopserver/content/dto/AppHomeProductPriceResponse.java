package org.muybaby.shopserver.content.dto;

public record AppHomeProductPriceResponse(
        Long minPriceCent,
        Long maxPriceCent,
        Long originalPriceCent
) {
}
