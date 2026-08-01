package org.muybaby.shopserver.common.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TimePolicyTest {

    @Test
    void convertsShanghaiBusinessDayBoundariesToUtc() {
        assertThat(TimePolicy.businessDayStartUtc(LocalDate.of(2026, 8, 1)))
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 16, 0));
    }

    @Test
    void derivesShanghaiBusinessDateFromUtcTimestamp() {
        assertThat(TimePolicy.businessDate(LocalDateTime.of(2026, 7, 31, 16, 30)))
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }
}
