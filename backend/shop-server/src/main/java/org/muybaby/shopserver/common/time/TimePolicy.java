package org.muybaby.shopserver.common.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Central time policy: persisted event timestamps are UTC, while commerce
 * reporting days are defined by the Asia/Shanghai business calendar.
 */
public final class TimePolicy {

    public static final ZoneOffset UTC = ZoneOffset.UTC;
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private TimePolicy() {
    }

    public static LocalDateTime businessDayStartUtc(LocalDate date) {
        return businessWallTimeToUtc(date.atStartOfDay());
    }

    public static LocalDateTime businessWallTimeToUtc(LocalDateTime value) {
        return value.atZone(BUSINESS_ZONE)
                .withZoneSameInstant(UTC)
                .toLocalDateTime();
    }

    public static LocalDate businessDate(LocalDateTime utcValue) {
        return utcValue.atOffset(UTC)
                .atZoneSameInstant(BUSINESS_ZONE)
                .toLocalDate();
    }
}
