package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderAmountAllocatorTest {

    @Test
    void allocatesCouponFreightPaidAndNullableCostWithExactOrderTotals() {
        List<OrderItemOperatingSnapshot> snapshots = OrderAmountAllocator.allocate(
                List.of(item(1L, 1, 1_000L), item(2L, 2, 2_000L)),
                java.util.Arrays.asList(400L, null),
                101L,
                10L);

        assertThat(snapshots).containsExactly(
                new OrderItemOperatingSnapshot(400L, 400L, 34L, 3L, 969L),
                new OrderItemOperatingSnapshot(null, null, 67L, 7L, 1_940L));
        assertThat(snapshots).extracting(OrderItemOperatingSnapshot::couponDiscountAllocatedCent)
                .containsExactly(34L, 67L);
        assertThat(snapshots.stream().mapToLong(OrderItemOperatingSnapshot::paidAmountAllocatedCent).sum())
                .isEqualTo(2_909L);
    }

    @Test
    void resolvesEqualRemaindersByOriginalItemOrder() {
        assertThat(OrderAmountAllocator.proportional(1L, List.of(100L, 100L, 100L)))
                .containsExactly(1L, 0L, 0L);
    }

    private OrderPreviewItemResponse item(long skuId, int quantity, long lineAmountCent) {
        return new OrderPreviewItemResponse(
                null,
                skuId,
                skuId + 100,
                "Product " + skuId,
                "",
                "",
                null,
                "",
                null,
                "",
                null,
                "SKU-" + skuId,
                "Default",
                lineAmountCent / quantity,
                lineAmountCent / quantity,
                quantity,
                lineAmountCent,
                lineAmountCent);
    }
}
