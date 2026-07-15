package org.muybaby.shopserver.payment.dto;

public record EnvironmentPaymentConfigResponse(
        boolean available,
        EffectivePaymentConfigResponse config
) {
}
