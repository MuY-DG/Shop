package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AdminReturnInspectionRequest(
        String decision,
        String note,
        List<AdminReturnInspectionItemRequest> items
) {
}
