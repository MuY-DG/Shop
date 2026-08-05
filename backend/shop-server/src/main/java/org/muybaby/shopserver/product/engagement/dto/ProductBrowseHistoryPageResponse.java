package org.muybaby.shopserver.product.engagement.dto;

import java.util.List;

public record ProductBrowseHistoryPageResponse(
        List<ProductBrowseHistoryItemResponse> records,
        long current,
        long size,
        boolean hasMore
) {
}
