package org.muybaby.shopserver.storage.dto;

public record AdminStorageConfigResponse(
        boolean configured,
        String publicBaseUrl,
        String region,
        String bucket,
        String secretIdMasked,
        boolean secretKeyConfigured
) {
}
