package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AdminSpecTemplateGroupResponse(
        Long id,
        String groupKey,
        String name,
        boolean imageEnabled,
        Integer sortOrder,
        List<AdminSpecTemplateValueResponse> values
) {
}
