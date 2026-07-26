package org.muybaby.shopserver.storage.compression;

import org.muybaby.shopserver.storage.compression.config.ImageCompressionUsageProbe;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

@Component
public class TinifyImageCompressionUsageProbe implements ImageCompressionUsageProbe {

    private final ImageCompressionService imageCompressionService;

    public TinifyImageCompressionUsageProbe(ImageCompressionService imageCompressionService) {
        this.imageCompressionService = imageCompressionService;
    }

    @Override
    public ProbeResult probe(String apiKey) {
        ImageCompressionProbeResult result = imageCompressionService.probe(apiKey);
        return new ProbeResult(
                switch (result.state()) {
                    case VALID -> State.VALID;
                    case QUOTA_EXHAUSTED -> State.QUOTA_EXHAUSTED;
                    case RATE_LIMITED -> State.RATE_LIMITED;
                    case INVALID_CREDENTIALS -> State.INVALID_KEY;
                },
                integerCount(result.compressionCount())
        );
    }

    private Integer integerCount(OptionalLong count) {
        if (count.isEmpty()) {
            return null;
        }
        long value = count.getAsLong();
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException("Tinify compression count exceeds the supported range");
        }
        return (int) value;
    }
}
