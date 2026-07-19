package org.muybaby.shopserver.content.dto;

import java.util.List;

public record AdminHomeAutoFillResponse(
        Integer targetCount,
        Integer existingCount,
        Integer addedCount,
        Integer finalCount,
        Boolean insufficient,
        List<Long> addedSpuIds
) {
}
