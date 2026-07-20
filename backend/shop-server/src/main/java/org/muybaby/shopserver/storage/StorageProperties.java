package org.muybaby.shopserver.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "shop.storage")
public record StorageProperties(
        StorageProviderKind provider,
        String publicBaseUrl,
        Local local,
        TencentCos tencentCos,
        Limits limits
) {
    public record Local(String root) {
    }

    public record TencentCos(
            String region,
            String bucket,
            String secretId,
            String secretKey,
            String publicBaseUrl
    ) {
    }

    public record Limits(
            DataSize imageMaxSize,
            DataSize videoMaxSize,
            DataSize privateFileMaxSize,
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

        public Limits(DataSize imageMaxSize, DataSize videoMaxSize, DataSize privateFileMaxSize) {
            this(imageMaxSize, videoMaxSize, privateFileMaxSize, null, null, null);
        }

        public Limits(DataSize imageMaxSize, DataSize privateFileMaxSize) {
            this(imageMaxSize, DataSize.ofMegabytes(50), privateFileMaxSize, null, null, null);
        }
    }
}
