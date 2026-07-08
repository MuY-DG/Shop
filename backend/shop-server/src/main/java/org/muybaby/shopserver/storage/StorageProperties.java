package org.muybaby.shopserver.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "shop.storage")
public record StorageProperties(
        StorageProviderKind provider,
        String publicBaseUrl,
        Local local,
        Limits limits
) {
    public record Local(String root) {
    }

    public record Limits(
            DataSize imageMaxSize,
            DataSize privateFileMaxSize
    ) {
    }
}
