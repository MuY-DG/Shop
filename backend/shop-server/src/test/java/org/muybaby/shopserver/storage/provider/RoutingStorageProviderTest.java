package org.muybaby.shopserver.storage.provider;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.PutObjectRequest;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingStorageProviderTest {

    private static final String PUBLIC_CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final String PRIVATE_IMAGE_CACHE_CONTROL = "private, max-age=300";

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
    void cosUploadsApplyLongLivedCacheOnlyToPublicObjects() {
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(cosConfig());
        COSClient client = mock(COSClient.class);
        RoutingStorageProvider provider = new RoutingStorageProvider(
                configService,
                (region, secretId, secretKey) -> client
        );
        byte[] content = "cache-policy".getBytes();

        provider.put(
                cosLocation("ap-guangzhou", "public/library/image/example.jpg"),
                "image/jpeg",
                new ByteArrayInputStream(content),
                content.length
        );
        provider.put(
                cosLocation("ap-guangzhou", "private/secret/document/example.pem"),
                "application/x-pem-file",
                new ByteArrayInputStream(content),
                content.length
        );

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client, times(2)).putObject(requestCaptor.capture());
        List<PutObjectRequest> requests = requestCaptor.getAllValues();

        assertThat(requests.get(0).getMetadata().getCacheControl()).isEqualTo(PUBLIC_CACHE_CONTROL);
        assertThat(requests.get(0).getCannedAcl()).isEqualTo(CannedAccessControlList.PublicRead);
        assertThat(requests.get(1).getMetadata().getCacheControl()).isNull();
        assertThat(requests.get(1).getCannedAcl()).isEqualTo(CannedAccessControlList.Private);
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

    @Test
    void privateCosAccessSignsTheConfiguredCustomOriginWithoutOpeningTheObject() throws Exception {
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        ResolvedStorageConfig config = new ResolvedStorageConfig(
                "https://oss.example.test",
                "ap-guangzhou",
                "shop-test-123",
                "secret-id",
                "secret-key"
        );
        when(configService.effective()).thenReturn(config);
        COSClient objectClient = mock(COSClient.class);
        COSClient signingClient = mock(COSClient.class);
        URL signedUrl = new URL(
                "https://oss.example.test/private/customer-service/image.png?q-signature=test"
        );
        when(signingClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(signedUrl);
        AtomicReference<HttpProtocol> protocol = new AtomicReference<>();
        AtomicReference<String> endpoint = new AtomicReference<>();
        RoutingStorageProvider provider = new RoutingStorageProvider(
                configService,
                (region, secretId, secretKey) -> objectClient,
                (region, secretId, secretKey, requestedProtocol, requestedEndpoint) -> {
                    protocol.set(requestedProtocol);
                    endpoint.set(requestedEndpoint);
                    return signingClient;
                }
        );

        PrivateObjectAccess access = provider.privateReadAccess(
                cosLocation("ap-guangzhou", "private/customer-service/image.png"),
                Duration.ofMinutes(5)
        );

        assertThat(access.mode()).isEqualTo(PrivateObjectAccess.Mode.SIGNED_URL);
        assertThat(access.url()).isEqualTo(signedUrl.toString());
        assertThat(access.expiresAt()).isNotNull();
        assertThat(protocol).hasValue(HttpProtocol.https);
        assertThat(endpoint).hasValue("oss.example.test");
        ArgumentCaptor<GeneratePresignedUrlRequest> requestCaptor =
                ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(signingClient).generatePresignedUrl(requestCaptor.capture());
        GeneratePresignedUrlRequest request = requestCaptor.getValue();
        assertThat(request.getBucketName()).isEqualTo("shop-test-123");
        assertThat(request.getKey()).isEqualTo("private/customer-service/image.png");
        assertThat(request.getMethod()).isEqualTo(HttpMethodName.GET);
        assertThat(request.getExpiration()).isNotNull();
        assertThat(request.isSignPrefixMode()).isFalse();
        assertThat(request.getResponseHeaders().getCacheControl())
                .isEqualTo(PRIVATE_IMAGE_CACHE_CONTROL);
        verify(objectClient, never()).getObject(anyString(), anyString());

        provider.shutdown();
        verify(signingClient).shutdown();
        verify(objectClient, never()).shutdown();
    }

    @Test
    void realCosSdkPresignedUrlUsesTheConfiguredCustomOriginAsItsSignedHost() throws Exception {
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(new ResolvedStorageConfig(
                "https://oss.example.test",
                "ap-guangzhou",
                "shop-test-123",
                "secret-id",
                "secret-key"
        ));
        RoutingStorageProvider provider = new RoutingStorageProvider(configService);

        PrivateObjectAccess access = provider.privateReadAccess(
                cosLocation("ap-guangzhou", "private/customer-service/image with space.png"),
                Duration.ofMinutes(5)
        );

        assertThat(access.mode()).isEqualTo(PrivateObjectAccess.Mode.SIGNED_URL);
        assertThat(URI.create(access.url()).getHost())
                .isEqualTo("oss.example.test");
        assertThat(access.url()).contains("q-signature=");
        assertThat(access.url()).contains("response-cache-control=");
        provider.shutdown();
    }

    @Test
    void historicalCosLocationKeepsItsOwnDefaultSignedOrigin() throws Exception {
        StorageRuntimeConfigService configService = mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(new ResolvedStorageConfig(
                "https://oss.example.test",
                "ap-guangzhou",
                "shop-test-123",
                "secret-id",
                "secret-key"
        ));
        COSClient signingClient = mock(COSClient.class);
        when(signingClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(new URL(
                        "https://shop-test-123.cos.ap-shanghai.myqcloud.com/private/old.png?q-signature=test"
                ));
        AtomicReference<String> endpoint = new AtomicReference<>();
        RoutingStorageProvider provider = new RoutingStorageProvider(
                configService,
                (region, secretId, secretKey) -> mock(COSClient.class),
                (region, secretId, secretKey, protocol, requestedEndpoint) -> {
                    endpoint.set(requestedEndpoint);
                    return signingClient;
                }
        );

        PrivateObjectAccess access = provider.privateReadAccess(
                cosLocation("ap-shanghai", "private/old.png"),
                Duration.ofMinutes(5)
        );

        assertThat(access.mode()).isEqualTo(PrivateObjectAccess.Mode.SIGNED_URL);
        assertThat(endpoint).hasValue(
                "shop-test-123.cos.ap-shanghai.myqcloud.com");
        provider.shutdown();
    }

    private ResolvedStorageConfig cosConfig() {
        return new ResolvedStorageConfig(
                "https://shop-test-123.cos.ap-guangzhou.myqcloud.com",
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
