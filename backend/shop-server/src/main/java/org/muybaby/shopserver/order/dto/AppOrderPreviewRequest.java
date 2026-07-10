package org.muybaby.shopserver.order.dto;

import org.muybaby.shopserver.order.CheckoutSource;

import java.util.List;

public record AppOrderPreviewRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity,
        Long addressId,
        Long userCouponId
) {
}
