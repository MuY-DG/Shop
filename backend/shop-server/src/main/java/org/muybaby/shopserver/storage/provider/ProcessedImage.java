package org.muybaby.shopserver.storage.provider;

public record ProcessedImage(
        String objectKey,
        String format,
        String contentType,
        long sizeBytes,
        int width,
        int height,
        int frameCount,
        String etag,
        String sourceFormat,
        int sourceWidth,
        int sourceHeight,
        int sourceFrameCount
) {
}
