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
        List<Long> unitCostCents,
        List<CategorySnapshot> categorySnapshots,
        List<Long> selectedCartItemIds,
        long productOriginalAmountCent,
        long productAmountCent,
        long freightCent,
        CheckoutContext context
) {

    public CheckoutSelection {
        previewItems = List.copyOf(previewItems);
        checkoutItems = List.copyOf(checkoutItems);
        unitCostCents = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(unitCostCents));
        categorySnapshots = List.copyOf(categorySnapshots);
        selectedCartItemIds = List.copyOf(selectedCartItemIds);
        if (previewItems.size() != unitCostCents.size()) {
            throw new IllegalArgumentException("Cost snapshots must align with checkout items");
        }
        if (previewItems.size() != categorySnapshots.size()) {
            throw new IllegalArgumentException("Category snapshots must align with checkout items");
        }
    }

    public record CategorySnapshot(Long categoryId, String categoryName) {
    }
}
