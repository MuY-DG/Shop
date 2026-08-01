package org.muybaby.shopserver.storage.dto;

import org.muybaby.shopserver.storage.StorageMediaKind;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record StorageAssetQueryRequest(
        Long current,
        Long size,
        String keyword,
        StorageMediaKind mediaKind,
        Long folderId,
        String referenceStatus,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo
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

    public LocalDateTime createdFromUtc() {
        return utc(createdFrom);
    }

    public LocalDateTime createdToUtc() {
        return utc(createdTo);
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
