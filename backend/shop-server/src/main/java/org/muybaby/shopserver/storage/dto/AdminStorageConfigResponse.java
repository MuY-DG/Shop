package org.muybaby.shopserver.storage.dto;

public record AdminStorageConfigResponse(
        String provider,
        boolean persisted,
        String defaultProvider,
        String publicBaseUrl,
        String localRoot,
        String cosRegion,
        String cosBucket,
        String cosSecretIdMasked,
        boolean cosSecretKeyConfigured
) {
}
