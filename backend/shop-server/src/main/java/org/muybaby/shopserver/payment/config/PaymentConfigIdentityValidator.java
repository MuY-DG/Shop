package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Binds a provider response or notification to the immutable configuration identity captured by
 * the payment order. Legacy database-backed rows remain recoverable through their immutable id;
 * legacy environment-backed rows have neither an id nor a fingerprint and therefore fail closed.
 */
@Component
public class PaymentConfigIdentityValidator {

    private final PaymentConfigResolver paymentConfigResolver;

    public PaymentConfigIdentityValidator(PaymentConfigResolver paymentConfigResolver) {
        this.paymentConfigResolver = paymentConfigResolver;
    }

    public void validate(
            Long storedConfigId,
            String storedFingerprint,
            ResolvedPaymentConfig verifiedConfig
    ) {
        if (verifiedConfig == null || !Objects.equals(storedConfigId, verifiedConfig.configId())) {
            throw configurationChanged();
        }
        if (!StringUtils.hasText(storedFingerprint)) {
            if (storedConfigId == null) {
                throw configurationChanged();
            }
            return;
        }

        byte[] expected = storedFingerprint.trim().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = paymentConfigResolver.fingerprint(verifiedConfig).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw configurationChanged();
        }
    }

    private BusinessException configurationChanged() {
        return new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
    }
}
