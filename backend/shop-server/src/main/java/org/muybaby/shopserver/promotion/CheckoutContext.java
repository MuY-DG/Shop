package org.muybaby.shopserver.promotion;

import java.util.List;

public record CheckoutContext(
        Long userId,
        List<CheckoutItem> items
) {
    public long totalAmountCent() {
        return items == null ? 0L : items.stream().mapToLong(CheckoutItem::lineAmountCent).sum();
    }

    public long amountCentForSpu(Long spuId) {
        if (spuId == null || items == null) {
            return 0L;
        }
        return items.stream()
                .filter(item -> spuId.equals(item.spuId()))
                .mapToLong(CheckoutItem::lineAmountCent)
                .sum();
    }
}
