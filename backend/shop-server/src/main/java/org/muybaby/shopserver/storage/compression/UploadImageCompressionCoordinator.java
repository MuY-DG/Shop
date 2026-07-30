package org.muybaby.shopserver.storage.compression;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionProperties;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionRuntimeConfigService;
import org.muybaby.shopserver.storage.compression.config.ResolvedImageCompressionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Applies the runtime compression policy without coupling the Tinify transport to storage.
 *
 * <p>Disabled or unusable configuration is a soft bypass, so the validated source image remains
 * available to the storage pipeline. Once compression is enabled and usable, transient provider
 * failures are retried and must not silently persist the source image. Credential and quota
 * failures update the persisted runtime state and then use the source because compression is no
 * longer usable.</p>
 */
@Component
public class UploadImageCompressionCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(UploadImageCompressionCoordinator.class);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png"
    );

    private final ImageCompressionRuntimeConfigService configService;
    private final ImageCompressionService imageCompressionService;
    private final int maxAttempts;
    private final Duration retryDelay;

    public UploadImageCompressionCoordinator(
            ImageCompressionRuntimeConfigService configService,
            ImageCompressionService imageCompressionService,
            ImageCompressionProperties properties
    ) {
        this.configService = configService;
        this.imageCompressionService = imageCompressionService;
        this.maxAttempts = requireMaxAttempts(properties.effectiveMaxAttempts());
        this.retryDelay = requireRetryDelay(properties.effectiveRetryDelay());
    }

    public CompressionOutcome compress(byte[] source, String contentType, long maxOutputBytes) {
        if (source == null || source.length == 0 || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            return CompressionOutcome.passthrough(source, contentType);
        }

        ImageCompressionRuntimeConfigService.CompressionPermit permit =
                configService.acquireCompressionPermit(2);
        if (permit == null) {
            return CompressionOutcome.passthrough(source, contentType);
        }
        ResolvedImageCompressionConfig config = permit.config();
        ImageCompressionRequest request =
                new ImageCompressionRequest(source, contentType, maxOutputBytes);

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ImageCompressionResult result =
                            imageCompressionService.compress(config.apiKey(), request);
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
                        logBypass(ex);
                        return CompressionOutcome.passthrough(source, contentType);
                    }
                    if (ex.failure() == ImageCompressionFailure.INVALID_CREDENTIALS) {
                        markInvalidKey(config.apiKey());
                        logBypass(ex);
                        return CompressionOutcome.passthrough(source, contentType);
                    }
                    if (!isRetryable(ex.failure()) || attempt == maxAttempts) {
                        log.warn(
                                "Tinify image compression failed; upload rejected: "
                                        + "attempts={}, failure={}, status={}",
                                attempt,
                                ex.failure(),
                                statusCode(ex)
                        );
                        throw new BusinessException(ErrorCode.STORAGE_IMAGE_COMPRESSION_FAILED);
                    }
                    log.warn(
                            "Tinify image compression attempt failed; retrying: "
                                    + "attempt={}/{}, failure={}, status={}",
                            attempt,
                            maxAttempts,
                            ex.failure(),
                            statusCode(ex)
                    );
                    waitBeforeRetry();
                }
            }
            throw new IllegalStateException("Compression retry loop completed without an outcome");
        } finally {
            try {
                configService.releaseCompressionPermit(permit.reservationId());
            } catch (RuntimeException ex) {
                log.warn("Failed to release an image compression budget reservation");
            }
        }
    }

    private void logBypass(ImageCompressionException exception) {
        log.warn(
                "Tinify image compression became unavailable; source image retained: "
                        + "failure={}, status={}",
                exception.failure(),
                statusCode(exception)
        );
    }

    private Integer statusCode(ImageCompressionException exception) {
        return exception.statusCode().isPresent() ? exception.statusCode().getAsInt() : null;
    }

    private boolean isRetryable(ImageCompressionFailure failure) {
        return switch (failure) {
            case RATE_LIMITED, UNAVAILABLE, NETWORK, TIMEOUT -> true;
            case QUOTA_EXHAUSTED, INVALID_CREDENTIALS, REJECTED, INVALID_RESPONSE -> false;
        };
    }

    private void waitBeforeRetry() {
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.STORAGE_IMAGE_COMPRESSION_FAILED);
        }
    }

    private int requireMaxAttempts(int value) {
        if (value < 1 || value > 3) {
            throw new IllegalArgumentException("Image compression max attempts must be between 1 and 3");
        }
        return value;
    }

    private Duration requireRetryDelay(Duration value) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException(
                    "Image compression retry delay must be between 0 and 10 seconds");
        }
        return value;
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
