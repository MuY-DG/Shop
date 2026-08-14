package org.muybaby.shopserver.payment.dto;

public record EffectivePaymentConfigStateResponse(
        boolean available,
        EffectivePaymentConfigResponse config
) {
}
