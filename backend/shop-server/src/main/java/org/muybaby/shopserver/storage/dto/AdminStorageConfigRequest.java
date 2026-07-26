package org.muybaby.shopserver.storage.dto;

public record AdminStorageConfigRequest(
        String provider,
        // Kept for compatibility with admin clients deployed before the provider URLs were split.
        String publicBaseUrl,
        String localPublicBaseUrl,
        String cosPublicBaseUrl,
        String localRoot,
        String cosRegion,
        String cosBucket,
        String cosSecretId,
        String cosSecretKey
) {
}
