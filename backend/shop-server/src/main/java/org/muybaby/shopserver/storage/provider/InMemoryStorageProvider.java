package org.muybaby.shopserver.storage.provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.imageio.ImageIO;

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
    public DirectUploadGrant createDirectUploadGrant(
            StorageObjectLocation location,
            String contentType,
            long exactSizeBytes,
            Duration validity
    ) {
        return new DirectUploadGrant(
                "https://direct-upload.test.invalid",
                Map.of(
                        "key", location.objectKey(),
                        "Content-Type", contentType
                ),
                Instant.now().plus(validity)
        );
    }

    @Override
    public DirectObjectMetadata metadata(StorageObjectLocation location) {
        Entry entry = requireEntry(location.objectKey());
        return new DirectObjectMetadata(
                entry.contentType(), entry.bytes().length, "test-etag");
    }

    @Override
    public List<ProcessedImage> processImage(
            StorageObjectLocation source,
            List<ImageProcessOutput> outputs
    ) {
        Entry entry = requireEntry(source.objectKey());
        try {
            var image = ImageIO.read(new ByteArrayInputStream(entry.bytes()));
            if (image == null) {
                throw new IllegalStateException("Unreadable in-memory image");
            }
            String sourceFormat = sourceFormat(entry.bytes());
            List<ProcessedImage> processed = new ArrayList<>();
            for (ImageProcessOutput output : outputs) {
                entries.put(
                        output.objectKey(),
                        new Entry("image/webp", entry.bytes())
                );
                processed.add(new ProcessedImage(
                        output.objectKey(),
                        "webp",
                        "image/webp",
                        entry.bytes().length,
                        image.getWidth(),
                        image.getHeight(),
                        1,
                        "test-etag",
                        sourceFormat,
                        image.getWidth(),
                        image.getHeight(),
                        1
                ));
            }
            return List.copyOf(processed);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process in-memory image", ex);
        }
    }

    @Override
    public void copy(
            StorageObjectLocation source,
            StorageObjectLocation destination,
            String contentType,
            boolean publicRead
    ) {
        Entry entry = requireEntry(source.objectKey());
        entries.put(
                destination.objectKey(),
                new Entry(contentType, entry.bytes().clone())
        );
    }

    @Override
    public void delete(String objectKey) {
        entries.remove(objectKey);
    }

    @Override
    public void delete(StorageObjectLocation location) {
        if (location.container() != null
                && location.container().startsWith("missing-")) {
            throw new IllegalStateException(
                    "In-memory test storage container is unavailable");
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

    private Entry requireEntry(String objectKey) {
        Entry entry = entries.get(objectKey);
        if (entry == null) {
            throw new IllegalStateException("In-memory test object does not exist");
        }
        return entry;
    }

    private String sourceFormat(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F') {
            return "gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "webp";
        }
        throw new IllegalStateException("Unsupported in-memory image");
    }

    private record Entry(String contentType, byte[] bytes) {
    }
}
