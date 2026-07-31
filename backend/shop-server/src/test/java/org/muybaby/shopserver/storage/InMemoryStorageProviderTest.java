package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.provider.InMemoryStorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStorageProviderTest {

    private InMemoryStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InMemoryStorageProvider();
    }

    @Test
    void putOpenAndDeleteRoundTrip() throws IOException {
        provider.put(
                "public/product/2026/07/08/test-object.txt",
                "text/plain",
                new ByteArrayInputStream("hotpot".getBytes(StandardCharsets.UTF_8)),
                6
        );

        StoredObject object = provider.open("public/product/2026/07/08/test-object.txt");
        assertThat(new String(object.inputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("hotpot");
        assertThat(object.contentType()).isEqualTo("text/plain");
        assertThat(object.sizeBytes()).isEqualTo(6);

        provider.delete("public/product/2026/07/08/test-object.txt");

        assertThatThrownBy(() -> provider.open("public/product/2026/07/08/test-object.txt"))
                .isInstanceOf(IllegalStateException.class);
    }
}
