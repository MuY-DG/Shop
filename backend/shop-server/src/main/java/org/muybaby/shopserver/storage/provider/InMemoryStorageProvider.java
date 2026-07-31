package org.muybaby.shopserver.storage.provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Non-persistent provider used only by the Spring {@code test} profile.
 */
public final class InMemoryStorageProvider implements StorageProvider {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public StoredObject put(
            String objectKey,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            entries.put(objectKey, new Entry(contentType, bytes));
            return storedObject(objectKey, contentType, bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to store in-memory test object", ex);
        }
    }

    @Override
    public StoredObject put(
            StorageObjectLocation location,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        return put(location.objectKey(), contentType, inputStream, sizeBytes);
    }

    @Override
    public StoredObject open(String objectKey) {
        Entry entry = entries.get(objectKey);
        if (entry == null) {
            throw new IllegalStateException("In-memory test object does not exist");
        }
        return storedObject(objectKey, entry.contentType(), entry.bytes());
    }

    @Override
    public StoredObject open(StorageObjectLocation location) {
        return open(location.objectKey());
    }

    @Override
    public void delete(String objectKey) {
        entries.remove(objectKey);
    }

    @Override
    public void delete(StorageObjectLocation location) {
        if (!entries.containsKey(location.objectKey())
                && location.container() != null
                && !location.container().isBlank()) {
            throw new IllegalStateException("In-memory test storage container is unavailable");
        }
        delete(location.objectKey());
    }

    private StoredObject storedObject(String objectKey, String contentType, byte[] bytes) {
        return new StoredObject(
                objectKey,
                contentType,
                new ByteArrayInputStream(bytes),
                bytes.length
        );
    }

    private record Entry(String contentType, byte[] bytes) {
    }
}
