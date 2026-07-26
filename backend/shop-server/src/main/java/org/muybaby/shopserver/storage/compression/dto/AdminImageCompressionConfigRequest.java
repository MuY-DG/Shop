package org.muybaby.shopserver.storage.compression.dto;

public record AdminImageCompressionConfigRequest(
        Boolean requestedEnabled,
        String configSource,
        String apiKey,
        Integer monthlyLimit
) {
}
