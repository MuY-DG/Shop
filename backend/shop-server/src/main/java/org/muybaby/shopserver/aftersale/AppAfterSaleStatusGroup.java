package org.muybaby.shopserver.aftersale;

import java.util.List;

public enum AppAfterSaleStatusGroup {
    PROCESSING(List.of(
            AfterSaleStatus.REQUESTED.name(),
            AfterSaleStatus.APPROVED.name(),
            AfterSaleStatus.WAITING_RETURN.name(),
            AfterSaleStatus.RETURNING.name(),
            AfterSaleStatus.WAITING_INSPECTION.name(),
            AfterSaleStatus.REFUNDING.name(),
            AfterSaleStatus.REFUND_FAILED.name()
    )),
    COMPLETED(List.of(
            AfterSaleStatus.REJECTED.name(),
            AfterSaleStatus.RETURN_REJECTED.name(),
            AfterSaleStatus.CANCELLED.name(),
            AfterSaleStatus.REFUNDED.name()
    ));

    private final List<String> statuses;

    AppAfterSaleStatusGroup(List<String> statuses) {
        this.statuses = List.copyOf(statuses);
    }

    public List<String> statuses() {
        return statuses;
    }
}
