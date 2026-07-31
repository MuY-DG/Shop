package org.muybaby.shopserver.storage.dto;

public record AdminStorageConfigRequest(
        String publicBaseUrl,
        String region,
        String bucket,
        String secretId,
        String secretKey
) {
}
