package org.muybaby.shopserver.storage.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@SpringBootTest
@ActiveProfiles("test")
class PublicImageVariantServiceTest {
    @Autowired JdbcClient jdbc;
    @Autowired StorageProvider storage;
    @Autowired PublicImageVariantService service;
    @Autowired StorageAssetCleanupService cleanup;
    @Autowired PlatformTransactionManager manager;

    @Test
    void backfillsExistingAssetAndDeletesAllThreeObjectsTogether() throws Exception {
        Asset asset = asset();
        assertThat(service.findByUrls(List.of(asset.url()))).isEmpty();
        assertThat(service.generate(asset.id())).isTrue();
        assertThat(service.generate(asset.id())).isFalse();
        assertThat(service.findByUrls(List.of(asset.url())).get(asset.url()))
                .isEqualTo(PublicImageVariants.urls(asset.url()));
        for (var location : PublicImageVariants.locations(asset.location())) {
            assertThat(storage.metadata(location).sizeBytes()).isPositive();
        }
        delete(asset);
        assertThat(service.findByUrls(List.of(asset.url()))).isEmpty();
        assertRemoved(asset);
    }

    @Test
    void partialProcessingFailureKeepsOriginalUsableAndSchedulesRetryWithoutExposingVariants() throws Exception {
        Asset asset = asset();
        StorageProvider partial = spy(storage);
        doAnswer(invocation -> {
            var images = storage.processImage(invocation.getArgument(0), invocation.getArgument(1));
            return List.of(images.getFirst());
        }).when(partial).processImage(any(), anyList());
        var worker = new PublicImageVariantService(jdbc, partial, manager);
        assertThat(worker.generate(asset.id())).isFalse();
        assertThat(worker.generate(asset.id())).isFalse();
        assertThat(storage.metadata(asset.location()).sizeBytes()).isPositive();
        assertThat(service.findByUrls(List.of(asset.url()))).isEmpty();
        assertThat(jdbc.sql("select public_image_variants_retry_at from storage_asset where id = :id")
                .param("id", asset.id()).query(LocalDateTime.class).single()).isNotNull();
        delete(asset);
        assertRemoved(asset);
    }

    @Test
    void deletionWaitsForInFlightGenerationSoNoLateOutputSurvivesCleanup() throws Exception {
        Asset asset = asset();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StorageProvider slow = spy(storage);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return storage.processImage(invocation.getArgument(0), invocation.getArgument(1));
        }).when(slow).processImage(any(), anyList());
        var worker = new PublicImageVariantService(jdbc, slow, manager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var generating = executor.submit(() -> worker.generate(asset.id()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var deleting = executor.submit(() -> { delete(asset); return true; });
            release.countDown();
            assertThat(generating.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deleting.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertRemoved(asset);
    }

    @Test
    void skipsSvgAndDeletedAssetsAndDoesNotReturnPrivateImages() throws Exception {
        Asset asset = asset();
        jdbc.sql("update storage_asset set content_type = 'image/svg+xml' where id = :id").param("id", asset.id()).update();
        assertThat(service.generate(asset.id())).isFalse();
        jdbc.sql("""
                update storage_asset set scope = 'ATTACHMENT', visibility = 'PRIVATE',
                    public_image_variants_ready = true where id = :id
                """).param("id", asset.id()).update();
        assertThat(service.findByUrls(List.of(asset.url()))).isEmpty();
        jdbc.sql("update storage_asset set status = 'DELETED' where id = :id").param("id", asset.id()).update();
        assertThat(service.generate(asset.id())).isFalse();
        storage.delete(asset.location());
    }

    private void delete(Asset asset) {
        jdbc.sql("update storage_asset set status = 'DELETE_PENDING' where id = :id").param("id", asset.id()).update();
        assertThat(cleanup.cleanupAsset(asset.id())).isTrue();
    }

    private void assertRemoved(Asset asset) {
        assertThatThrownBy(() -> storage.metadata(asset.location())).isInstanceOf(RuntimeException.class);
        for (var location : PublicImageVariants.locations(asset.location())) {
            assertThatThrownBy(() -> storage.metadata(location)).isInstanceOf(RuntimeException.class);
        }
    }

    private Asset asset() throws Exception {
        String key = "public/variant-test/" + UUID.randomUUID() + ".png";
        String url = "https://images.example.test/" + key;
        jdbc.sql("""
                insert into storage_asset (scope, media_kind, visibility, provider, object_key,
                    original_filename, content_type, extension, size_bytes, public_url, uploaded_by_type, uploaded_by_id)
                values ('LIBRARY', 'IMAGE', 'PUBLIC', 'TENCENT_COS', :key, 'photo.png', 'image/png', 'png', 10, :url, 'ADMIN', 1)
                """).param("key", key).param("url", url).update();
        Long id = jdbc.sql("select id from storage_asset where object_key = :key").param("key", key).query(Long.class).single();
        var location = new StorageObjectLocation(StorageProviderKind.TENCENT_COS, "", "", key);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        storage.put(location, "image/png", new ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        return new Asset(id, url, location);
    }

    private record Asset(Long id, String url, StorageObjectLocation location) {}
}
