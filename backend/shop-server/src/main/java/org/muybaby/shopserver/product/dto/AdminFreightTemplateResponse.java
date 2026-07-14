package org.muybaby.shopserver.product.dto;

import org.muybaby.shopserver.product.FreightChargeMode;
import org.muybaby.shopserver.product.FreightTemplateStatus;

import java.time.LocalDateTime;

public record AdminFreightTemplateResponse(
        Long id,
        String name,
        FreightChargeMode chargeMode,
        Long fixedAmountCent,
        FreightTemplateStatus status,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
