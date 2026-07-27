package org.muybaby.shopserver.storage.dto;

import java.util.List;

public record StorageAssetBatchDeleteResponse(
        List<Long> deletedAssetIds,
        List<Long> skippedReferencedAssetIds
) {
}
