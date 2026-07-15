package org.muybaby.shopserver.aftersale;

import java.util.Arrays;
import java.util.List;

public enum AdminAfterSaleStatusGroup {
    ALL(Arrays.stream(AfterSaleStatus.values()).map(Enum::name).toList()),
    PENDING_REVIEW(List.of(AfterSaleStatus.REQUESTED.name())),
    REFUNDING(List.of(AfterSaleStatus.APPROVED.name(), AfterSaleStatus.REFUNDING.name())),
    REFUNDED(List.of(AfterSaleStatus.REFUNDED.name())),
    REJECTED(List.of(AfterSaleStatus.REJECTED.name())),
    REFUND_FAILED(List.of(AfterSaleStatus.REFUND_FAILED.name()));

    private final List<String> statuses;

    AdminAfterSaleStatusGroup(List<String> statuses) {
        this.statuses = List.copyOf(statuses);
    }

    public List<String> statuses() {
        return statuses;
    }
}
