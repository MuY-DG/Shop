package org.muybaby.shopserver.storage.provider;

import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingStorageProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void recordedLocalRootSurvivesRuntimeRootChanges() throws Exception {
        Path originalRoot = tempDir.resolve("original");
        Path replacementRoot = tempDir.resolve("replacement");
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(
                localConfig(originalRoot),
                localConfig(replacementRoot),
                localConfig(replacementRoot)
        );
        RoutingStorageProvider provider = new RoutingStorageProvider(configService);
        StorageObjectLocation location = new StorageObjectLocation(
                StorageProviderKind.LOCAL,
                originalRoot.toAbsolutePath().normalize().toString(),
                "",
                "private/secret.txt"
        );
        byte[] content = "recorded-location".getBytes();

        provider.put(location, "text/plain", new ByteArrayInputStream(content), content.length);

        StoredObject storedObject = provider.open(location);
        try (InputStream inputStream = storedObject.inputStream()) {
            assertThat(inputStream.readAllBytes()).isEqualTo(content);
        }
        assertThat(replacementRoot.resolve("private/secret.txt")).doesNotExist();

        provider.delete(location);

        assertThat(originalRoot.resolve("private/secret.txt")).doesNotExist();
    }

    @Test
    void differentCosRegionsKeepIndependentClientsUntilProviderShutdown() {
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(cosConfig());
        COSClient guangzhouClient = mock(COSClient.class);
        COSClient shanghaiClient = mock(COSClient.class);
        List<String> createdRegions = new ArrayList<>();
        RoutingStorageProvider provider = new RoutingStorageProvider(configService, (region, secretId, secretKey) -> {
            createdRegions.add(region);
            return "ap-guangzhou".equals(region) ? guangzhouClient : shanghaiClient;
        });
        StorageObjectLocation guangzhou = cosLocation("ap-guangzhou", "private/guangzhou.pem");
        StorageObjectLocation shanghai = cosLocation("ap-shanghai", "private/shanghai.pem");

        provider.delete(guangzhou);
        provider.delete(shanghai);
        provider.delete(guangzhou);

        assertThat(createdRegions).containsExactly("ap-guangzhou", "ap-shanghai");
        verify(guangzhouClient, times(2)).deleteObject("shop-test-123", "private/guangzhou.pem");
        verify(shanghaiClient).deleteObject("shop-test-123", "private/shanghai.pem");
        verify(guangzhouClient, never()).shutdown();
        verify(shanghaiClient, never()).shutdown();

        provider.shutdown();

        verify(guangzhouClient).shutdown();
        verify(shanghaiClient).shutdown();
    }

    @Test
    void concurrentAccessCreatesOnlyOneCosClientPerKey() throws Exception {
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(cosConfig());
        COSClient client = mock(COSClient.class);
        AtomicInteger createCount = new AtomicInteger();
        RoutingStorageProvider provider = new RoutingStorageProvider(configService, (region, secretId, secretKey) -> {
            createCount.incrementAndGet();
            return client;
        });
        StorageObjectLocation location = cosLocation("ap-guangzhou", "private/concurrent.pem");
        int taskCount = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    provider.delete(location);
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(createCount).hasValue(1);
        verify(client, times(taskCount)).deleteObject("shop-test-123", "private/concurrent.pem");
        provider.shutdown();
        verify(client).shutdown();
    }

    private ResolvedStorageConfig localConfig(Path root) {
        return new ResolvedStorageConfig(
                StorageProviderKind.LOCAL,
                "http://localhost:8080",
                root.toString(),
                "",
                "",
                "",
                ""
        );
    }

    private ResolvedStorageConfig cosConfig() {
        return new ResolvedStorageConfig(
                StorageProviderKind.TENCENT_COS,
                "https://shop-test-123.cos.ap-guangzhou.myqcloud.com",
                tempDir.resolve("unused-local").toString(),
                "ap-guangzhou",
                "shop-test-123",
                "secret-id",
                "secret-key"
        );
    }

    private StorageObjectLocation cosLocation(String region, String objectKey) {
        return new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "shop-test-123",
                region,
                objectKey
        );
    }
}
