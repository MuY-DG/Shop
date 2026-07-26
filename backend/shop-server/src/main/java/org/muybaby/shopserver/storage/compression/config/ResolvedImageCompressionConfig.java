package org.muybaby.shopserver.storage.compression.config;

import java.time.LocalDateTime;
import java.time.YearMonth;

public record ResolvedImageCompressionConfig(
        boolean requestedEnabled,
        boolean effectiveEnabled,
        ImageCompressionConfigSource configSource,
        ImageCompressionConfigSource resolvedSource,
        String apiKey,
        int monthlyLimit,
        int compressionCount,
        int remainingCount,
        YearMonth quotaPeriod,
        LocalDateTime lastCheckedAt,
        ImageCompressionAutoDisabledReason autoDisabledReason
) {

    public boolean enabled() {
        return effectiveEnabled;
    }
}
