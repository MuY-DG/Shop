package org.muybaby.shopserver.order.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record AdminOrderQueryRequest(
        Long current,
        Long size,
        String orderNo,
        String status,
        String statusGroup,
        String userSearchType,
        String userKeyword,
        String receiverName,
        String receiverPhone,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdStart,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdEnd,
        String trackingNo
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
