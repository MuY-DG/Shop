package org.muybaby.shopserver.storage.compression;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validated input for one image compression. The byte array is defensively copied.
 */
public record ImageCompressionRequest(byte[] content, String contentType, long maxOutputBytes) {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    private static final long MAX_BYTE_ARRAY_SIZE = Integer.MAX_VALUE - 8L;

    public ImageCompressionRequest {
        Objects.requireNonNull(content, "content");
        if (content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }
        contentType = normalizeContentType(contentType);
        if (!SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported content type: " + contentType);
        }
        if (maxOutputBytes <= 0 || maxOutputBytes > MAX_BYTE_ARRAY_SIZE) {
            throw new IllegalArgumentException("maxOutputBytes is outside the supported range");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    byte[] contentForTransport() {
        return content;
    }

    boolean isWebp() {
        return "image/webp".equals(contentType);
    }

    private static String normalizeContentType(String contentType) {
        Objects.requireNonNull(contentType, "contentType");
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int parametersStart = normalized.indexOf(';');
        if (parametersStart >= 0) {
            normalized = normalized.substring(0, parametersStart).trim();
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        return normalized;
    }
}
