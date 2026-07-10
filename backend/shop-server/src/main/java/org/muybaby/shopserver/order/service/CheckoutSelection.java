package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;
import org.muybaby.shopserver.promotion.CheckoutContext;
import org.muybaby.shopserver.promotion.CheckoutItem;

import java.util.List;

public record CheckoutSelection(
        CheckoutSource source,
        List<OrderPreviewItemResponse> previewItems,
        List<CheckoutItem> checkoutItems,
        List<Long> selectedCartItemIds,
        long productOriginalAmountCent,
        long productAmountCent,
        CheckoutContext context
) {

    public CheckoutSelection {
        previewItems = List.copyOf(previewItems);
        checkoutItems = List.copyOf(checkoutItems);
        selectedCartItemIds = List.copyOf(selectedCartItemIds);
    }
}
