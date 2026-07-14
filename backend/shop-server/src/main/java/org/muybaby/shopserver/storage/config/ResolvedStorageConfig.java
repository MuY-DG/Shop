package org.muybaby.shopserver.storage.config;

import org.muybaby.shopserver.storage.StorageProviderKind;

public record ResolvedStorageConfig(
        StorageProviderKind provider,
        String publicBaseUrl,
        String localRoot,
        String cosRegion,
        String cosBucket,
        String cosSecretId,
        String cosSecretKey
) {
}
