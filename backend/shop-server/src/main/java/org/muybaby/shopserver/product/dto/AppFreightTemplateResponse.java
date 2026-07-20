package org.muybaby.shopserver.product.dto;

import org.muybaby.shopserver.product.FreightChargeMode;

public record AppFreightTemplateResponse(
        Long id,
        String name,
        FreightChargeMode chargeMode,
        Long fixedAmountCent
) {
}
