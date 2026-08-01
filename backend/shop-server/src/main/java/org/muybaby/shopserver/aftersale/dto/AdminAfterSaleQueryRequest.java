package org.muybaby.shopserver.aftersale.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record AdminAfterSaleQueryRequest(
        Long current,
        Long size,
        String status,
        String statusGroup,
        Long afterSaleId,
        String afterSaleNo,
        String orderNo,
        String userSearchType,
        String userKeyword,
        String afterSaleType,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdStart,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdEnd,
        String refundNo
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }

    public LocalDateTime createdStartUtc() {
        return utc(createdStart);
    }

    public LocalDateTime createdEndUtc() {
        return utc(createdEnd);
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
