package org.muybaby.shopserver.storage.compression;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * A verified WebP response from the compression provider.
 */
public record ImageCompressionResult(
        byte[] content,
        String contentType,
        int width,
        int height,
        OptionalLong compressionCount
) {

    public ImageCompressionResult {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(compressionCount, "compressionCount");
        if (content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }
        if (!"image/webp".equals(contentType)) {
            throw new IllegalArgumentException("compressed content must be image/webp");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public long size() {
        return content.length;
    }
}
