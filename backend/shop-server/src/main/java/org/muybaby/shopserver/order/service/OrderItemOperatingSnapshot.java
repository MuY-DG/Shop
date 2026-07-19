package org.muybaby.shopserver.order.service;

public record OrderItemOperatingSnapshot(
        Long unitCostCent,
        Long lineCostCent,
        long couponDiscountAllocatedCent,
        long freightAllocatedCent,
        long paidAmountAllocatedCent
) {
}
