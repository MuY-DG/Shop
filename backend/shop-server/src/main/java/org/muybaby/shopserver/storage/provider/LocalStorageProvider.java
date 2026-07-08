package org.muybaby.shopserver.storage.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalStorageProvider implements StorageProvider {

    private final Path root;

    public LocalStorageProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredObject put(String objectKey, String contentType, InputStream inputStream, long sizeBytes) {
        Path target = resolveWithinRoot(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target);
            return new StoredObject(objectKey, contentType, Files.newInputStream(target), sizeBytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public StoredObject open(String objectKey) {
        Path target = resolveWithinRoot(objectKey);
        try {
            return new StoredObject(
                    objectKey,
                    Files.probeContentType(target),
                    Files.newInputStream(target),
                    Files.size(target)
            );
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path target = resolveWithinRoot(objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolveWithinRoot(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Object key escapes configured storage root");
        }
        return resolved;
    }
}
