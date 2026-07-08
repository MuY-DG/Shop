package org.muybaby.shopserver.payment.dto;

public record PaymentCancelResponse(
        Long orderId,
        String status
) {
}
