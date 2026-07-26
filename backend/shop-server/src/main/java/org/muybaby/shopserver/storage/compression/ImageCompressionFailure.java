package org.muybaby.shopserver.storage.compression;

/**
 * Stable failure categories exposed to upload and configuration orchestration.
 */
public enum ImageCompressionFailure {
    QUOTA_EXHAUSTED,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    REJECTED,
    UNAVAILABLE,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE
}
