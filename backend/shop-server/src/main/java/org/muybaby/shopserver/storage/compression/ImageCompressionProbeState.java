package org.muybaby.shopserver.storage.compression;

public enum ImageCompressionProbeState {
    VALID,
    QUOTA_EXHAUSTED,
    RATE_LIMITED,
    INVALID_CREDENTIALS
}
