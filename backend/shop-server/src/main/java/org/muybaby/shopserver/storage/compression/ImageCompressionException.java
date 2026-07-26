package org.muybaby.shopserver.storage.compression;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A provider-safe exception. It never includes the API key or image bytes.
 */
public final class ImageCompressionException extends RuntimeException {

    private final ImageCompressionFailure failure;
    private final Integer statusCode;
    private final String providerError;
    private final Long compressionCount;

    ImageCompressionException(
            ImageCompressionFailure failure,
            String message,
            Integer statusCode,
            String providerError,
            Long compressionCount,
            Throwable cause
    ) {
        super(message, cause);
        this.failure = failure;
        this.statusCode = statusCode;
        this.providerError = providerError;
        this.compressionCount = compressionCount;
    }

    public ImageCompressionFailure failure() {
        return failure;
    }

    public OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    public Optional<String> providerError() {
        return Optional.ofNullable(providerError);
    }

    public OptionalLong compressionCount() {
        return compressionCount == null ? OptionalLong.empty() : OptionalLong.of(compressionCount);
    }
}
