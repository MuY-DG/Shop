package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.order.dto.OrderPreviewItemResponse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OrderAmountAllocator {

    private OrderAmountAllocator() {
    }

    public static List<OrderItemOperatingSnapshot> allocate(
            List<OrderPreviewItemResponse> items,
            List<Long> unitCostCents,
            long couponDiscountCent,
            long freightCent
    ) {
        if (items == null || unitCostCents == null || items.size() != unitCostCents.size()
                || items.isEmpty() || couponDiscountCent < 0 || freightCent < 0) {
            throw new IllegalArgumentException("Invalid order allocation input");
        }
        List<Long> weights = items.stream().map(OrderPreviewItemResponse::lineAmountCent).toList();
        List<Long> couponShares = proportional(couponDiscountCent, weights);
        List<Long> freightShares = proportional(freightCent, weights);
        List<OrderItemOperatingSnapshot> snapshots = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            OrderPreviewItemResponse item = items.get(index);
            Long unitCostCent = unitCostCents.get(index);
            Long lineCostCent = unitCostCent == null
                    ? null
                    : Math.multiplyExact(unitCostCent, item.quantity().longValue());
            long paidAmountCent = Math.addExact(
                    Math.subtractExact(item.lineAmountCent(), couponShares.get(index)),
                    freightShares.get(index));
            snapshots.add(new OrderItemOperatingSnapshot(
                    unitCostCent,
                    lineCostCent,
                    couponShares.get(index),
                    freightShares.get(index),
                    paidAmountCent));
        }
        return List.copyOf(snapshots);
    }

    static List<Long> proportional(long amount, List<Long> sourceWeights) {
        if (amount < 0 || sourceWeights == null || sourceWeights.isEmpty()
                || sourceWeights.stream().anyMatch(weight -> weight == null || weight < 0)) {
            throw new IllegalArgumentException("Invalid proportional allocation input");
        }
        List<Long> weights = sourceWeights;
        long weightSum = sumExact(weights);
        if (weightSum == 0) {
            weights = sourceWeights.stream().map(ignored -> 1L).toList();
            weightSum = weights.size();
        }
        BigInteger divisor = BigInteger.valueOf(weightSum);
        List<Share> shares = new ArrayList<>(weights.size());
        long allocated = 0L;
        for (int index = 0; index < weights.size(); index++) {
            BigInteger numerator = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(weights.get(index)));
            BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(divisor);
            long floor = quotientAndRemainder[0].longValueExact();
            allocated = Math.addExact(allocated, floor);
            shares.add(new Share(index, floor, quotientAndRemainder[1]));
        }
        long remainderCents = Math.subtractExact(amount, allocated);
        List<Share> remainderOrder = shares.stream()
                .sorted(Comparator.comparing(Share::fractionalRemainder).reversed()
                        .thenComparingInt(Share::index))
                .toList();
        for (int index = 0; index < remainderCents; index++) {
            Share share = remainderOrder.get(index);
            shares.set(share.index(), new Share(share.index(), share.amount() + 1, share.fractionalRemainder()));
        }
        return shares.stream().map(Share::amount).toList();
    }

    private static long sumExact(List<Long> values) {
        long result = 0L;
        for (Long value : values) {
            result = Math.addExact(result, value);
        }
        return result;
    }

    private record Share(int index, long amount, BigInteger fractionalRemainder) {
    }
}
