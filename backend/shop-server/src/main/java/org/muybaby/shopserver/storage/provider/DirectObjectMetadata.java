package org.muybaby.shopserver.storage.provider;

public record DirectObjectMetadata(
        String contentType,
        long sizeBytes,
        String etag
) {
}
