package org.muybaby.shopserver.storage.dto;

import org.muybaby.shopserver.storage.StorageUsageStatus;

public record StorageAssetUsageQueryRequest(
        StorageUsageStatus status,
        Long current,
        Long size
) {
    public StorageUsageStatus pageStatus() {
        return status == null ? StorageUsageStatus.ACTIVE : status;
    }

    public long pageCurrent() {
        return current == null || current < 1 ? 1L : current;
    }

    public long pageSize() {
        if (size == null || size < 1) {
            return 20L;
        }
        return Math.min(size, 100L);
    }
}
