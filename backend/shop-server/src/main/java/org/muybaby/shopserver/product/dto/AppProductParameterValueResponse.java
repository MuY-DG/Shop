package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AppProductParameterValueResponse(
        Long parameterId,
        String parameterCode,
        String parameterName,
        String valueType,
        String unit,
        String displayText,
        String cardRole,
        String cardRenderer,
        Integer cardPriority,
        List<AppProductParameterOptionValueResponse> selectedOptions
) {
}
