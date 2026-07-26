package org.muybaby.shopserver.storage.compression;

import org.muybaby.shopserver.storage.compression.config.ImageCompressionRuntimeConfigService;
import org.muybaby.shopserver.storage.compression.config.ResolvedImageCompressionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;
import java.util.Set;

/**
 * Applies the runtime compression policy without coupling the Tinify transport to storage.
 *
 * <p>Every provider failure is a soft failure for uploads: the already validated source image
 * remains available to the storage pipeline. Credential and quota failures also update the
 * persisted runtime state so following uploads stop calling Tinify until configuration or the
 * billing period changes.</p>
 */
@Component
public class UploadImageCompressionCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(UploadImageCompressionCoordinator.class);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final ImageCompressionRuntimeConfigService configService;
    private final ImageCompressionService imageCompressionService;

    public UploadImageCompressionCoordinator(
            ImageCompressionRuntimeConfigService configService,
            ImageCompressionService imageCompressionService
    ) {
        this.configService = configService;
        this.imageCompressionService = imageCompressionService;
    }

    public CompressionOutcome compress(byte[] source, String contentType, long maxOutputBytes) {
        if (source == null || source.length == 0 || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            return CompressionOutcome.passthrough(source, contentType);
        }

        int expectedCost = "image/webp".equals(contentType) ? 1 : 2;
        ImageCompressionRuntimeConfigService.CompressionPermit permit =
                configService.acquireCompressionPermit(expectedCost);
        if (permit == null) {
            return CompressionOutcome.passthrough(source, contentType);
        }
        ResolvedImageCompressionConfig config = permit.config();

        try {
            ImageCompressionResult result = imageCompressionService.compress(
                    config.apiKey(),
                    new ImageCompressionRequest(source, contentType, maxOutputBytes)
            );
            recordCount(config.apiKey(), result.compressionCount());
            return CompressionOutcome.compressed(
                    result.content(),
                    result.contentType(),
                    result.width(),
                    result.height()
            );
        } catch (ImageCompressionException ex) {
            recordCount(config.apiKey(), ex.compressionCount());
            if (ex.failure() == ImageCompressionFailure.QUOTA_EXHAUSTED) {
                markQuotaExhausted(config.apiKey());
            } else if (ex.failure() == ImageCompressionFailure.INVALID_CREDENTIALS) {
                markInvalidKey(config.apiKey());
            }
            log.warn(
                    "Tinify image compression was bypassed: failure={}, status={}",
                    ex.failure(),
                    ex.statusCode().isPresent() ? ex.statusCode().getAsInt() : null
            );
            return CompressionOutcome.passthrough(source, contentType);
        } finally {
            try {
                configService.releaseCompressionPermit(permit.reservationId());
            } catch (RuntimeException ex) {
                log.warn("Failed to release an image compression budget reservation");
            }
        }
    }

    private void recordCount(String apiKey, OptionalLong compressionCount) {
        if (compressionCount.isEmpty()) {
            return;
        }
        long value = compressionCount.getAsLong();
        if (value > Integer.MAX_VALUE) {
            log.warn("Tinify compression count exceeded the supported integer range");
            return;
        }
        try {
            configService.recordProviderCount(apiKey, (int) value);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to record Tinify compression usage (type={})",
                    ex.getClass().getSimpleName()
            );
        }
    }

    private void markQuotaExhausted(String apiKey) {
        try {
            configService.markQuotaExhausted(apiKey);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to persist the exhausted Tinify quota state (type={})",
                    ex.getClass().getSimpleName()
            );
        }
    }

    private void markInvalidKey(String apiKey) {
        try {
            configService.markInvalidKey(apiKey);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to persist the invalid Tinify credential state (type={})",
                    ex.getClass().getSimpleName()
            );
        }
    }

    public record CompressionOutcome(
            byte[] content,
            String contentType,
            boolean compressed,
            Integer providerWidth,
            Integer providerHeight
    ) {

        public CompressionOutcome {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }

        static CompressionOutcome passthrough(byte[] content, String contentType) {
            return new CompressionOutcome(content, contentType, false, null, null);
        }

        static CompressionOutcome compressed(
                byte[] content,
                String contentType,
                int providerWidth,
                int providerHeight
        ) {
            return new CompressionOutcome(
                    content, contentType, true, providerWidth, providerHeight);
        }
    }
}
