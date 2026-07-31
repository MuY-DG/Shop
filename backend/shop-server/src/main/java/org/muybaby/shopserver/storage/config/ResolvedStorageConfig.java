package org.muybaby.shopserver.storage.config;

public record ResolvedStorageConfig(
        String publicBaseUrl,
        String region,
        String bucket,
        String secretId,
        String secretKey
) {
}
