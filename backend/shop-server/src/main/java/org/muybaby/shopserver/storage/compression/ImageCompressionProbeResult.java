package org.muybaby.shopserver.storage.compression;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Result of Tinify's non-billable empty-input credential probe.
 */
public record ImageCompressionProbeResult(
        ImageCompressionProbeState state,
        OptionalLong compressionCount
) {

    public ImageCompressionProbeResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(compressionCount, "compressionCount");
    }
}
