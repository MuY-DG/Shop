package org.muybaby.shopserver.storage.provider;

import java.io.InputStream;

public record StoredObject(
        String objectKey,
        String contentType,
        InputStream inputStream,
        long sizeBytes
) {
}
