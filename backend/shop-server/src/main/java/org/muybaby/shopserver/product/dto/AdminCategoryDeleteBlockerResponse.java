package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AdminCategoryDeleteBlockerResponse(
        String type,
        String label,
        long count,
        List<String> examples
) {
}
