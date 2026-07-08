package org.muybaby.shopserver.payment.dto;

public record PaymentSyncResponse(
        Long orderId,
        String status,
        String transactionId
) {
}
