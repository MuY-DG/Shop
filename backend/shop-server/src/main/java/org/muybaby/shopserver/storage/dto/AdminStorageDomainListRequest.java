package org.muybaby.shopserver.storage.dto;

public record AdminStorageDomainListRequest(
        String bucket,
        String region,
        String secretId,
        String secretKey
) {
}
