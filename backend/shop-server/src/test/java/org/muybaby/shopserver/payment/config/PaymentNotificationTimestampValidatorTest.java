package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.payment.PaymentNotificationProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentNotificationTimestampValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

    private final PaymentNotificationTimestampValidator validator = new PaymentNotificationTimestampValidator(
            new PaymentNotificationProperties(Duration.ofMinutes(5)),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void acceptsTimestampsWithinInclusiveConfiguredWindow() {
        assertThatCode(() -> validator.validate(epochSeconds(NOW.minus(Duration.ofMinutes(5)))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(epochSeconds(NOW.plus(Duration.ofMinutes(5)))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsStaleFutureMissingAndMalformedTimestamps() {
        assertThatThrownBy(() -> validator.validate(epochSeconds(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(epochSeconds(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate("not-a-timestamp"))
                .isInstanceOf(BusinessException.class);
    }

    private String epochSeconds(Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }
}
