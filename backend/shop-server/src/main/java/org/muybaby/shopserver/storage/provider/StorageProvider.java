package org.muybaby.shopserver.storage.provider;

import java.io.InputStream;

public interface StorageProvider {

    StoredObject put(String objectKey, String contentType, InputStream inputStream, long sizeBytes);

    StoredObject open(String objectKey);

    void delete(String objectKey);
}
