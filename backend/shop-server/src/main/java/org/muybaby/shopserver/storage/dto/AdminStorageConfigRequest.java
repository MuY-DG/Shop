package org.muybaby.shopserver.storage.dto;

public record AdminStorageConfigRequest(
        String provider,
        String publicBaseUrl,
        String localRoot,
        String cosRegion,
        String cosBucket,
        String cosSecretId,
        String cosSecretKey
) {
}
