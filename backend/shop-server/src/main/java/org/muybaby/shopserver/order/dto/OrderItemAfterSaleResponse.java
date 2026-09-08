package org.muybaby.shopserver.order.dto;

import java.util.List;

/** Completed refunds and current applications are independent, so neither hides the other. */
public record OrderItemAfterSaleResponse(
        int refundedQuantity,
        long refundedAmountCent,
        boolean fullyRefunded,
        List<Record> records
) {
    public record Record(Long afterSaleId, String afterSaleNo, String status,
                         int quantity, long amountCent, boolean appVisible) { }
}
