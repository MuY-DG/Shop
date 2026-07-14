package org.muybaby.shopserver.storage.provider;

import org.muybaby.shopserver.storage.StorageProviderKind;

import java.io.InputStream;

public interface StorageProvider {

    StoredObject put(String objectKey, String contentType, InputStream inputStream, long sizeBytes);

    default StoredObject put(
            StorageProviderKind provider,
            String objectKey,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        return put(objectKey, contentType, inputStream, sizeBytes);
    }

    StoredObject open(String objectKey);

    default StoredObject open(StorageProviderKind provider, String objectKey) {
        return open(objectKey);
    }

    void delete(String objectKey);

    default void delete(StorageProviderKind provider, String objectKey) {
        delete(objectKey);
    }
}
