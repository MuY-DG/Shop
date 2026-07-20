package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentConfigIdentityValidatorTest {

    private final PaymentConfigResolver resolver = mock(PaymentConfigResolver.class);
    private final PaymentConfigIdentityValidator validator = new PaymentConfigIdentityValidator(resolver);

    @Test
    void acceptsMatchingVersionedConfigurationAndLegacyDatabaseIdentity() {
        ResolvedPaymentConfig config = config(91001L);
        when(resolver.fingerprint(config)).thenReturn("a".repeat(64));

        assertThatCode(() -> validator.validate(91001L, "a".repeat(64), config))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(91001L, "", config))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsVersionedEnvironmentConfiguration() {
        ResolvedPaymentConfig config = config(null);
        when(resolver.fingerprint(config)).thenReturn("b".repeat(64));

        assertThatCode(() -> validator.validate(null, "b".repeat(64), config))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedConfigurationIdOrFingerprintAndLegacyEnvironmentRows() {
        ResolvedPaymentConfig databaseConfig = config(91002L);
        ResolvedPaymentConfig environmentConfig = config(null);
        when(resolver.fingerprint(databaseConfig)).thenReturn("c".repeat(64));

        assertConfigurationChanged(() -> validator.validate(91001L, "c".repeat(64), databaseConfig));
        assertConfigurationChanged(() -> validator.validate(91002L, "d".repeat(64), databaseConfig));
        assertConfigurationChanged(() -> validator.validate(null, "", environmentConfig));
    }

    private ResolvedPaymentConfig config(Long configId) {
        ResolvedPaymentConfig config = mock(ResolvedPaymentConfig.class);
        when(config.configId()).thenReturn(configId);
        return config;
    }

    private void assertConfigurationChanged(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
    }
}
