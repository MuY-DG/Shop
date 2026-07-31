package org.muybaby.shopserver.common.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.secret-encryption")
public record SecretEncryptionProperties(
        Integer writeVersion,
        String activeKeyId,
        String keyRing,
        String legacyKey,
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
