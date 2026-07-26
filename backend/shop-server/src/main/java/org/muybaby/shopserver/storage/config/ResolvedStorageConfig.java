package org.muybaby.shopserver.storage.config;

import org.muybaby.shopserver.storage.StorageProviderKind;

public record ResolvedStorageConfig(
        StorageProviderKind provider,
        String localPublicBaseUrl,
        String cosPublicBaseUrl,
        String localRoot,
        String cosRegion,
        String cosBucket,
        String cosSecretId,
        String cosSecretKey
) {
    public String publicBaseUrl() {
        return provider == StorageProviderKind.TENCENT_COS
                ? cosPublicBaseUrl
                : localPublicBaseUrl;
    }
}
