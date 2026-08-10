package org.muybaby.shopserver.finance.reconciliation.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record StagedTradeBill(
        Path path,
        long sizeBytes,
        String contentSha256,
        boolean providerHashVerified
) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
