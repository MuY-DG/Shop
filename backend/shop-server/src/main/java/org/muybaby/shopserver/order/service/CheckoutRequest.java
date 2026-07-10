package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;

import java.util.LinkedHashSet;
import java.util.List;

public record CheckoutRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity,
        Long addressId,
        Long userCouponId
) {

    public CheckoutRequest {
        source = source == null ? CheckoutSource.CART : source;
        cartItemIds = normalizeIds(cartItemIds);
    }

    public static CheckoutRequest from(AppOrderPreviewRequest request) {
        if (request == null) {
            return new CheckoutRequest(CheckoutSource.CART, List.of(), null, null, null, null);
        }
        return new CheckoutRequest(
                request.source(),
                request.cartItemIds(),
                request.skuId(),
                request.quantity(),
                request.addressId(),
                request.userCouponId()
        );
    }

    public static CheckoutRequest from(AppOrderSubmitRequest request) {
        if (request == null) {
            return new CheckoutRequest(CheckoutSource.CART, List.of(), null, null, null, null);
        }
        return new CheckoutRequest(
                request.source(),
                request.cartItemIds(),
                request.skuId(),
                request.quantity(),
                request.addressId(),
                request.userCouponId()
        );
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                uniqueIds.add(id);
            }
        }
        return List.copyOf(uniqueIds);
    }
}
