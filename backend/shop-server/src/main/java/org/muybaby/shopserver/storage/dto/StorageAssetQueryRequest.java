package org.muybaby.shopserver.storage.dto;

import org.muybaby.shopserver.storage.StorageMediaKind;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public record StorageAssetQueryRequest(
        Long current,
        Long size,
        String keyword,
        StorageMediaKind mediaKind,
        Long folderId,
        String referenceStatus,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo
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
