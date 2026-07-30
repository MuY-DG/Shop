package org.muybaby.shopserver.storage.provider;

import org.muybaby.shopserver.storage.StorageProviderKind;

import java.io.InputStream;
import java.time.Duration;
import java.util.function.Function;

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

    default StoredObject put(
            StorageObjectLocation location,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        return put(location.provider(), location.objectKey(), contentType, inputStream, sizeBytes);
    }

    StoredObject open(String objectKey);

    default StoredObject open(StorageProviderKind provider, String objectKey) {
        return open(objectKey);
    }

    default StoredObject open(StorageObjectLocation location) {
        return open(location.provider(), location.objectKey());
    }

    default PrivateObjectAccess privateReadAccess(
            StorageObjectLocation location,
            Duration validity
    ) {
        return PrivateObjectAccess.authenticatedBlob();
    }

    default Function<StorageObjectLocation, PrivateObjectAccess> privateReadAccessResolver(
            Duration validity
    ) {
        return location -> privateReadAccess(location, validity);
    }

    void delete(String objectKey);

    default void delete(StorageProviderKind provider, String objectKey) {
        delete(objectKey);
    }

    default void delete(StorageObjectLocation location) {
        delete(location.provider(), location.objectKey());
    }
}
