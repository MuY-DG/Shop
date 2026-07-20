package org.muybaby.shopserver.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.pay.secret-encryption")
public record PaymentSecretEncryptionProperties(
        Integer writeVersion,
        String activeKeyId,
        String keyRing,
        boolean rotationEnabled,
        Duration rotationDelay,
        Integer rotationBatchSize
) {

    public int effectiveWriteVersion() {
        return writeVersion == null ? 1 : writeVersion;
    }

    public Duration effectiveRotationDelay() {
        return rotationDelay == null || rotationDelay.isNegative() || rotationDelay.isZero()
                ? Duration.ofMinutes(1)
                : rotationDelay;
    }

    public int effectiveRotationBatchSize() {
        return rotationBatchSize == null ? 50 : rotationBatchSize;
    }
}
