package org.muybaby.shopserver.storage.compression;

public interface ImageCompressionService {

    ImageCompressionResult compress(String apiKey, ImageCompressionRequest request);

    ImageCompressionProbeResult probe(String apiKey);
}
