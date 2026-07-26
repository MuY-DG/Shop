package org.muybaby.shopserver.storage.compression.config;

/**
 * Reads Tinify's provider-side compression count for the current billing month.
 * The HTTP client implements this boundary; runtime configuration remains independent
 * from provider transport details.
 */
@FunctionalInterface
public interface ImageCompressionUsageProbe {

    ProbeResult probe(String apiKey);

    enum State {
        VALID,
        QUOTA_EXHAUSTED,
        INVALID_KEY,
        RATE_LIMITED
    }

    record ProbeResult(State state, Integer count) {

        public ProbeResult {
            if (state == null || (count != null && count < 0)) {
                throw new IllegalArgumentException("Invalid image compression usage probe result");
            }
        }
    }
}
