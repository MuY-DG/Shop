package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AppProductParameterValueResponse(
        Long parameterId,
        String parameterCode,
        String parameterName,
        String valueType,
        String unit,
        String displayText,
        List<AppProductParameterOptionValueResponse> selectedOptions
) {
}
