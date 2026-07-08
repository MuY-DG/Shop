package org.muybaby.shopserver.storage.dto;

public record StorageFileQueryRequest(
        Long current,
        Long size,
        String purpose,
        Long assetCategoryId,
        String visibility,
        String status
) {
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
