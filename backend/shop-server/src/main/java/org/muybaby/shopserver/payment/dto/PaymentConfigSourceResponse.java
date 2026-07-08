package org.muybaby.shopserver.payment.dto;

public record PaymentConfigSourceResponse(
        String source,
        boolean persisted,
        String defaultSource
) {
}
