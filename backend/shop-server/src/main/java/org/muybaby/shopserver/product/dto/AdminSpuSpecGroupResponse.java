package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AdminSpuSpecGroupResponse(
        Long id,
        String groupKey,
        String name,
        Boolean imageEnabled,
        Integer sortOrder,
        List<AdminSpuSpecValueResponse> values
) {
}
