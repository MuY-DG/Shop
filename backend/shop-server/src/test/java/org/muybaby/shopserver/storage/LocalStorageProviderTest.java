package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.muybaby.shopserver.storage.provider.LocalStorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageProviderTest {

    @TempDir
    Path tempDir;

    private LocalStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalStorageProvider(tempDir);
    }

    @Test
    void putOpenAndDeleteRoundTripWithinConfiguredRoot() throws IOException {
        provider.put(
                "public/product/2026/07/08/test-object.txt",
                "text/plain",
                new ByteArrayInputStream("hotpot".getBytes(StandardCharsets.UTF_8)),
                6
        );

        StoredObject object = provider.open("public/product/2026/07/08/test-object.txt");
        assertThat(new String(object.inputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hotpot");
        assertThat(object.contentType()).isEqualTo("text/plain");
        assertThat(object.sizeBytes()).isEqualTo(6);

        provider.delete("public/product/2026/07/08/test-object.txt");

        assertThat(Files.exists(tempDir.resolve("public/product/2026/07/08/test-object.txt"))).isFalse();
    }

    @Test
    void providerRejectsObjectKeysThatEscapeTheConfiguredRoot() {
        assertThatThrownBy(() -> provider.put("../escape.txt", "text/plain", new ByteArrayInputStream(new byte[]{1}), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.open("../escape.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.delete("../escape.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
