package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;

public record AdminGuaranteeServiceResponse(
        Long id,
        String termsName,
        String contentDescription,
        String icon,
        Long iconFileId,
        Integer sortOrder,
        boolean visible,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
