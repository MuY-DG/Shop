package org.muybaby.shopserver.product.dto;

public record WholesaleTierResponse(
        Integer minQuantity,
        Long unitPriceCent
) {
}
