package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Binds a provider response or notification to the immutable database configuration identity
 * captured by the payment order.
 */
@Component
public class PaymentConfigIdentityValidator {

    private static final Pattern CONFIG_FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final PaymentConfigResolver paymentConfigResolver;

    public PaymentConfigIdentityValidator(PaymentConfigResolver paymentConfigResolver) {
        this.paymentConfigResolver = paymentConfigResolver;
    }

    public void validate(
            Long storedConfigId,
            String storedFingerprint,
            ResolvedPaymentConfig verifiedConfig
    ) {
        if (storedConfigId == null
                || verifiedConfig == null
                || !Objects.equals(storedConfigId, verifiedConfig.configId())) {
            throw configurationChanged();
        }
        if (storedFingerprint == null
                || !CONFIG_FINGERPRINT_PATTERN.matcher(storedFingerprint).matches()) {
            throw configurationChanged();
        }

        byte[] expected = storedFingerprint.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = paymentConfigResolver.fingerprint(verifiedConfig).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw configurationChanged();
        }
    }

    private BusinessException configurationChanged() {
        return new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
    }
}
