package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminSpecTemplateDetailResponse(
        Long id,
        String name,
        List<AdminSpecTemplateGroupResponse> groups,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
