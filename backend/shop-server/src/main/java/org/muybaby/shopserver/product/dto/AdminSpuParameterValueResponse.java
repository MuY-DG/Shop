package org.muybaby.shopserver.product.dto;

import java.math.BigDecimal;
import java.util.List;

public record AdminSpuParameterValueResponse(
        Long parameterId,
        String textValue,
        BigDecimal numberValue,
        Boolean booleanValue,
        List<String> optionCodes,
        String displayText
) {
}
