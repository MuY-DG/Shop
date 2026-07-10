package org.muybaby.shopserver.auth.token;

import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenPropertiesTest {

    private static final Duration POSITIVE = Duration.ofHours(1);

    @Test
    void rejectsNullZeroAndNegativeTokenTtls() {
        ThrowableAssert.ThrowingCallable[] invalidConfigurations = {
                () -> new TokenProperties(null, POSITIVE, POSITIVE, POSITIVE),
                () -> new TokenProperties(POSITIVE, null, POSITIVE, POSITIVE),
                () -> new TokenProperties(POSITIVE, POSITIVE, null, POSITIVE),
                () -> new TokenProperties(POSITIVE, POSITIVE, POSITIVE, null),
                () -> new TokenProperties(Duration.ZERO, POSITIVE, POSITIVE, POSITIVE),
                () -> new TokenProperties(POSITIVE, Duration.ZERO, POSITIVE, POSITIVE),
                () -> new TokenProperties(POSITIVE, POSITIVE, Duration.ZERO, POSITIVE),
                () -> new TokenProperties(POSITIVE, POSITIVE, POSITIVE, Duration.ZERO),
                () -> new TokenProperties(Duration.ofSeconds(-1), POSITIVE, POSITIVE, POSITIVE)
        };

        for (ThrowableAssert.ThrowingCallable invalidConfiguration : invalidConfigurations) {
            assertThatThrownBy(invalidConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Test
    void allowsAccessTtlToBeLongerThanRefreshTtl() {
        TokenProperties properties = new TokenProperties(
                Duration.ofDays(2),
                Duration.ofDays(1),
                Duration.ofDays(2),
                Duration.ofDays(1)
        );

        assertThat(properties.adminAccessTtl()).isEqualTo(Duration.ofDays(2));
        assertThat(properties.appRefreshTtl()).isEqualTo(Duration.ofDays(1));
    }
}
