package org.muybaby.shopserver.coupon.dto;

import org.muybaby.shopserver.order.CheckoutSource;

import java.util.List;

public record AvailableCouponRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity
) {

    public AvailableCouponRequest(List<Long> cartItemIds) {
        this(CheckoutSource.CART, cartItemIds, null, null);
    }
}
