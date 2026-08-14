package org.muybaby.shopserver.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "shop.storage")
public record StorageProperties(
        Limits limits
) {
    public record Limits(
            DataSize imageMaxSize,
            DataSize videoMaxSize,
            Integer imageMaxWidth,
            Integer imageMaxHeight,
            Long imageMaxPixels
    ) {
        private static final int DEFAULT_IMAGE_MAX_WIDTH = 8192;
        private static final int DEFAULT_IMAGE_MAX_HEIGHT = 8192;
        private static final long DEFAULT_IMAGE_MAX_PIXELS = 25_000_000L;

        @ConstructorBinding
        public Limits {
            imageMaxWidth = imageMaxWidth == null ? DEFAULT_IMAGE_MAX_WIDTH : imageMaxWidth;
            imageMaxHeight = imageMaxHeight == null ? DEFAULT_IMAGE_MAX_HEIGHT : imageMaxHeight;
            imageMaxPixels = imageMaxPixels == null ? DEFAULT_IMAGE_MAX_PIXELS : imageMaxPixels;
            if (imageMaxWidth <= 0 || imageMaxHeight <= 0 || imageMaxPixels <= 0) {
                throw new IllegalArgumentException("Image dimension limits must be positive");
            }
        }

        public Limits(DataSize imageMaxSize, DataSize videoMaxSize) {
            this(imageMaxSize, videoMaxSize, null, null, null);
        }
    }
}
