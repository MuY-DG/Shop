package org.muybaby.shopserver.order;

import java.util.Arrays;
import java.util.List;

public enum AdminOrderStatusGroup {
    ALL(Arrays.stream(OrderStatus.values()).map(Enum::name).toList()),
    UNPAID(List.of(OrderStatus.CREATED.name(), OrderStatus.PAYING.name())),
    TO_SHIP(List.of(OrderStatus.PAID.name())),
    TO_RECEIVE(List.of(OrderStatus.SHIPPED.name())),
    COMPLETED(List.of(OrderStatus.COMPLETED.name())),
    CLOSED(List.of(OrderStatus.CLOSED.name())),
    REFUNDING(List.of(OrderStatus.REFUNDING.name())),
    REFUNDED(List.of(OrderStatus.REFUNDED.name()));

    private final List<String> statuses;

    AdminOrderStatusGroup(List<String> statuses) {
        this.statuses = List.copyOf(statuses);
    }

    public List<String> statuses() {
        return statuses;
    }
}
