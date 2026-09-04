package org.muybaby.shopserver.storage.dto;

public record AdminStorageBucketListRequest(
        String secretId,
        String secretKey
) {
}
