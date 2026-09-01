package org.muybaby.shopserver.admin.log.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record AdminSystemLogQuery(
        Long current,
        Long size,
        String type,
        String result,
        String keyword,
        String module,
        String operator,
        String clientIp,
        String requestId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredStart,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredEnd
) {
    public LocalDateTime occurredStartUtc() {
        return utc(occurredStart);
    }

    public LocalDateTime occurredEndUtc() {
        return utc(occurredEnd);
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
