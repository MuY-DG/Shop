package org.muybaby.shopserver.promotion;

import java.util.List;

public record CheckoutContext(
        Long userId,
        List<CheckoutItem> items
) {
    public long totalAmountCent() {
        return items == null ? 0L : items.stream().mapToLong(CheckoutItem::lineAmountCent).sum();
    }
}
