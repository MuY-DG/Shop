package org.muybaby.shopserver.cart.dto;

import java.util.List;

public record CartListResponse(
        List<CartItemResponse> items,
        Integer totalQuantity,
        Long totalAmountCent,
        Integer unavailableCount
) {
}
