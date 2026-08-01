package org.muybaby.shopserver.common.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JsonTest
class UtcLocalDateTimeJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesUtcLocalDateTimeWithExplicitZuluOffset() throws Exception {
        String json = objectMapper.writeValueAsString(
                LocalDateTime.of(2026, 8, 1, 12, 0, 0));

        assertThat(json).isEqualTo("\"2026-08-01T12:00:00Z\"");
    }

    @Test
    void convertsOffsetInputToUtc() throws Exception {
        LocalDateTime value = objectMapper.readValue(
                "\"2026-08-01T20:00:00+08:00\"", LocalDateTime.class);

        assertThat(value).isEqualTo(LocalDateTime.of(2026, 8, 1, 12, 0, 0));
    }

    @Test
    void rejectsOffsetFreeInput() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "\"2026-08-01T20:00:00\"", LocalDateTime.class))
                .hasMessageContaining("Z or a numeric offset");
    }
}
