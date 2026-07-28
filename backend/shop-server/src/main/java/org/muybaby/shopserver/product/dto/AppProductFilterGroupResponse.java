package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AppProductFilterGroupResponse(
        Long parameterId,
        String parameterCode,
        String parameterName,
        String valueType,
        List<AppProductFilterOptionResponse> options
) {
}
