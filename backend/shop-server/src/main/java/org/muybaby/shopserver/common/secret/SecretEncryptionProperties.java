package org.muybaby.shopserver.common.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.secret-encryption")
public record SecretEncryptionProperties(
        String activeKeyId,
        String keyRing,
        boolean rotationEnabled,
        Duration rotationDelay,
        Integer rotationBatchSize
) {

    @Override
    public String toString() {
        return "SecretEncryptionProperties[activeKeyIdConfigured=" + configured(activeKeyId)
                + ", keyRing=<redacted>"
                + ", rotationEnabled=" + rotationEnabled
                + ", rotationDelay=" + rotationDelay
                + ", rotationBatchSize=" + rotationBatchSize + "]";
    }

    public Duration effectiveRotationDelay() {
        return rotationDelay == null || rotationDelay.isNegative() || rotationDelay.isZero()
                ? Duration.ofMinutes(1)
                : rotationDelay;
    }

    public int effectiveRotationBatchSize() {
        return rotationBatchSize == null ? 50 : rotationBatchSize;
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
