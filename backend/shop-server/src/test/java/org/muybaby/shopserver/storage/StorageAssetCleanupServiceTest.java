package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.service.StorageAssetCleanupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StorageAssetCleanupServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private StorageAssetCleanupService cleanupService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanStorageRows() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_asset").update();
    }

    @Test
    void cleanupDeletesOnlyExpiredUnreferencedPrivateAssets() {
        InsertedAsset expired = insertExpiredAsset("SECRET");
        InsertedAsset referenced = insertExpiredAsset("ATTACHMENT");
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (asset_id, usage_type, owner_type, owner_id, owner_label, snapshot_url,
                             sort_order, protected, status)
                        values
                            (:assetId, 'REFUND_EVIDENCE', 'AFTER_SALE', 99, 'cleanup-test', '',
                             1, true, 'ACTIVE')
                        """)
                .param("assetId", referenced.id())
                .update();

        assertThat(cleanup()).isEqualTo(1);

        assertThat(status(expired.id())).isEqualTo("DELETED");
        assertThat(status(referenced.id())).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> storageProvider.open(expired.objectKey())).isInstanceOf(RuntimeException.class);

        jdbcClient.sql("update storage_asset_usage set status = 'REMOVED' where asset_id = :assetId")
                .param("assetId", referenced.id())
                .update();
        assertThat(cleanup()).isEqualTo(1);
        assertThat(status(referenced.id())).isEqualTo("DELETED");
    }

    @Test
    void cleanupDeletesExpiredUnboundPublicReviewImage() {
        InsertedAsset reviewImage = insertExpiredPublicReviewImage();

        assertThat(cleanup()).isEqualTo(1);

        assertThat(status(reviewImage.id())).isEqualTo("DELETED");
        assertThatThrownBy(() -> storageProvider.open(reviewImage.objectKey()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void cleanupDeletesGeneratedThumbnailTogetherWithOriginal() {
        InsertedAsset expired = insertExpiredAsset("ATTACHMENT");
        String thumbnailObjectKey = expired.objectKey().replace(".txt", ".thumb-720.webp");
        byte[] thumbnailBytes = "thumbnail".getBytes();
        storageProvider.put(
                thumbnailObjectKey,
                "image/webp",
                new ByteArrayInputStream(thumbnailBytes),
                thumbnailBytes.length
        );
        jdbcClient.sql("""
                        update storage_asset
                        set thumbnail_status = 'READY',
                            thumbnail_object_key = :thumbnailObjectKey,
                            thumbnail_content_type = 'image/webp',
                            thumbnail_size_bytes = :thumbnailSize,
                            thumbnail_sha256 = 'thumbnail-sha'
                        where id = :assetId
                        """)
                .param("thumbnailObjectKey", thumbnailObjectKey)
                .param("thumbnailSize", thumbnailBytes.length)
                .param("assetId", expired.id())
                .update();

        assertThat(cleanup()).isEqualTo(1);

        assertThat(status(expired.id())).isEqualTo("DELETED");
        assertThatThrownBy(() -> storageProvider.open(expired.objectKey()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> storageProvider.open(thumbnailObjectKey))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset
                        where id = :assetId
                          and thumbnail_status = 'NONE'
                          and thumbnail_object_key is null
                        """)
                .param("assetId", expired.id())
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void failedProviderDeleteUsesABackoffLeaseWithoutBlockingNewExpiredAssets() {
        long failedAssetId = insertFailedCosRetry();
        long secondFailedAssetId = insertFailedCosRetry();
        InsertedAsset expired = insertExpiredAsset("SECRET");

        assertThat(cleanup()).isEqualTo(1);
        assertThat(status(expired.id())).isEqualTo("DELETED");
        assertThat(status(failedAssetId)).isEqualTo("DELETE_PENDING");
        assertThat(status(secondFailedAssetId)).isEqualTo("DELETE_PENDING");

        CleanupRetry retry = jdbcClient.sql("""
                        select cleanup_attempts, cleanup_next_retry_at
                        from storage_asset
                        where id = :assetId
                        """)
                .param("assetId", failedAssetId)
                .query((rs, rowNum) -> new CleanupRetry(
                        rs.getInt("cleanup_attempts"),
                        rs.getObject("cleanup_next_retry_at", LocalDateTime.class)
                ))
                .single();
        assertThat(retry.attempts()).isEqualTo(1);
        assertThat(retry.nextRetryAt()).isAfter(LocalDateTime.now());
        assertThat(jdbcClient.sql("select cleanup_attempts from storage_asset where id = :assetId")
                .param("assetId", secondFailedAssetId)
                .query(Integer.class)
                .single()).isZero();

        assertThat(cleanup()).isZero();
        assertThat(jdbcClient.sql("select cleanup_attempts from storage_asset where id = :assetId")
                .param("assetId", failedAssetId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select cleanup_attempts from storage_asset where id = :assetId")
                .param("assetId", secondFailedAssetId)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void staleUploadPendingIsDeletedWithoutClaimingAnUploadInsideItsGraceWindow() {
        InsertedAsset stale = insertPendingUpload(LocalDateTime.now().minusHours(1));
        InsertedAsset fresh = insertPendingUpload(LocalDateTime.now());

        assertThat(cleanup()).isEqualTo(1);

        assertThat(status(stale.id())).isEqualTo("DELETED");
        assertThat(status(fresh.id())).isEqualTo("UPLOAD_PENDING");
        assertThatThrownBy(() -> storageProvider.open(stale.objectKey())).isInstanceOf(RuntimeException.class);
        assertThat(storageProvider.open(fresh.objectKey()).sizeBytes()).isPositive();
        storageProvider.delete(fresh.objectKey());
    }

    @Test
    void cleanupRotatesCandidatePriorityBetweenConsecutiveRuns() {
        InsertedAsset firstExpired = insertExpiredAsset("SECRET");
        InsertedAsset secondExpired = insertExpiredAsset("SECRET");
        InsertedAsset staleUpload = insertPendingUpload(LocalDateTime.now().minusHours(1));

        assertThat(cleanupService.cleanupExpiredAssets(
                1, Duration.ofMinutes(30), 1L).attemptedCount()).isOne();
        assertThat(status(firstExpired.id())).isEqualTo("DELETED");
        assertThat(status(secondExpired.id())).isEqualTo("ACTIVE");
        assertThat(status(staleUpload.id())).isEqualTo("UPLOAD_PENDING");

        assertThat(cleanupService.cleanupExpiredAssets(
                1, Duration.ofMinutes(30), 2L).attemptedCount()).isOne();
        assertThat(status(secondExpired.id())).isEqualTo("ACTIVE");
        assertThat(status(staleUpload.id())).isEqualTo("DELETED");

        storageProvider.delete(secondExpired.objectKey());
    }

    @Test
    void cleanupStopsBeforeDestructiveWorkWhenItsOuterLeaseIsLost() {
        InsertedAsset expired = insertExpiredAsset("SECRET");
        try {
            assertThat(cleanupService.cleanupExpiredAssets(
                    10, Duration.ofMinutes(30), 1L, () -> false).attemptedCount())
                    .isZero();
            assertThat(status(expired.id())).isEqualTo("ACTIVE");
            assertThat(storageProvider.open(expired.objectKey()).sizeBytes()).isPositive();
        } finally {
            storageProvider.delete(expired.objectKey());
        }
    }

    @Test
    void staleUnreferencedUserAvatarIsDeletedAfterItsUploadGraceWindow() {
        InsertedAsset stale = insertUserAvatar(LocalDateTime.now().minusHours(1));
        InsertedAsset fresh = insertUserAvatar(LocalDateTime.now());
        InsertedAsset referenced = insertUserAvatar(LocalDateTime.now().minusHours(1));
        jdbcClient.sql("""
                        insert into app_user (id, openid, nickname, avatar_url, status)
                        values (991, 'cleanup-current-avatar', 'Cleanup User', :avatarUrl, 'ENABLED')
                        """)
                .param("avatarUrl", publicUrl(referenced.id()))
                .update();

        assertThat(cleanup()).isEqualTo(1);

        assertThat(status(stale.id())).isEqualTo("DELETED");
        assertThat(status(fresh.id())).isEqualTo("ACTIVE");
        assertThat(status(referenced.id())).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> storageProvider.open(stale.objectKey())).isInstanceOf(RuntimeException.class);
        assertThat(storageProvider.open(fresh.objectKey()).sizeBytes()).isPositive();
        assertThat(storageProvider.open(referenced.objectKey()).sizeBytes()).isPositive();
        storageProvider.delete(fresh.objectKey());
        storageProvider.delete(referenced.objectKey());
    }

    @Test
    void staleWorkerCannotFinalizeAfterAnotherWorkerOwnsTheLease() throws Exception {
        InsertedAsset expired = insertExpiredAsset("SECRET");
        StorageProvider slowProvider = mock(StorageProvider.class);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleteStarted.countDown();
            assertThat(releaseDelete.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(slowProvider).delete(any(StorageObjectLocation.class));
        StorageAssetCleanupService firstWorker = new StorageAssetCleanupService(
                jdbcClient,
                slowProvider,
                transactionTemplate
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = executor.submit(() -> firstWorker
                    .cleanupExpiredAssets(2, Duration.ofMinutes(30))
                    .cleanedCount());
            assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
            jdbcClient.sql("""
                            update storage_asset
                            set cleanup_lease_token = 'replacement-worker-token',
                                cleanup_next_retry_at = :leaseUntil
                            where id = :assetId
                              and status = 'DELETE_PENDING'
                            """)
                    .param("leaseUntil", LocalDateTime.now().plusMinutes(5))
                    .param("assetId", expired.id())
                    .update();
            releaseDelete.countDown();

            assertThat(result.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(status(expired.id())).isEqualTo("DELETE_PENDING");
            assertThat(jdbcClient.sql("select cleanup_lease_token from storage_asset where id = :assetId")
                    .param("assetId", expired.id())
                    .query(String.class)
                    .single()).isEqualTo("replacement-worker-token");
            verify(slowProvider).delete(any(StorageObjectLocation.class));
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
            storageProvider.delete(expired.objectKey());
        }
    }

    private long insertFailedCosRetry() {
        String objectKey = "private/cleanup/" + UUID.randomUUID() + ".pem";
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, storage_region,
                             object_key, original_filename, content_type, extension, size_bytes, sha256,
                             status, uploaded_by_type, uploaded_by_id, expires_at)
                        values
                            ('SECRET', 'DOCUMENT', 'PRIVATE', 'TENCENT_COS', 'missing-bucket-12345', 'ap-test',
                             :objectKey, 'retry.pem', 'text/plain', 'pem', 1, '',
                             'DELETE_PENDING', 'ADMIN', 1, :expiresAt)
                        """)
                .param("objectKey", objectKey)
                .param("expiresAt", LocalDateTime.now().minusHours(1))
                .update();
        return jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private InsertedAsset insertExpiredAsset(String scope) {
        String objectKey = "private/cleanup/" + UUID.randomUUID() + ".txt";
        byte[] bytes = "cleanup".getBytes();
        storageProvider.put(objectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, status,
                             uploaded_by_type, uploaded_by_id, expires_at)
                        values
                            (:scope, 'DOCUMENT', 'PRIVATE', 'TENCENT_COS', '', :objectKey,
                             'cleanup.txt', 'text/plain', 'txt', :sizeBytes, '', 'ACTIVE',
                             'ADMIN', 1, :expiresAt)
                        """)
                .param("scope", scope)
                .param("objectKey", objectKey)
                .param("sizeBytes", bytes.length)
                .param("expiresAt", LocalDateTime.now().minusHours(1))
                .update();
        Long id = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new InsertedAsset(id, objectKey);
    }

    private InsertedAsset insertPendingUpload(LocalDateTime createdAt) {
        String objectKey = "private/cleanup/" + UUID.randomUUID() + ".txt";
        byte[] bytes = "pending-upload".getBytes();
        storageProvider.put(objectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        LocalDateTime cleanupNotBefore = createdAt.plusMinutes(30);
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, status,
                             uploaded_by_type, uploaded_by_id, cleanup_next_retry_at, created_at, updated_at)
                        values
                            ('ATTACHMENT', 'DOCUMENT', 'PRIVATE', 'TENCENT_COS', '', :objectKey,
                             'pending.txt', 'text/plain', 'txt', :sizeBytes, '', 'UPLOAD_PENDING',
                             'ADMIN', 1, :cleanupNotBefore, :createdAt, :createdAt)
                        """)
                .param("objectKey", objectKey)
                .param("sizeBytes", bytes.length)
                .param("cleanupNotBefore", cleanupNotBefore)
                .param("createdAt", createdAt)
                .update();
        Long id = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new InsertedAsset(id, objectKey);
    }

    private InsertedAsset insertUserAvatar(LocalDateTime createdAt) {
        String objectKey = "public/library/image/cleanup/" + UUID.randomUUID() + ".png";
        String publicUrl = "http://localhost:8080/files/public/"
                + objectKey.substring("public/".length());
        byte[] bytes = "avatar".getBytes();
        storageProvider.put(objectKey, "image/png", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, public_url,
                             status, uploaded_by_type, uploaded_by_id, upload_context_type,
                             upload_context_id, created_at, updated_at)
                        values
                            ('LIBRARY', 'IMAGE', 'PUBLIC', 'TENCENT_COS', '', :objectKey,
                             'avatar.png', 'image/png', 'png', :sizeBytes, '', :publicUrl,
                             'ACTIVE', 'APP', 991, 'APP_USER_AVATAR', 991, :createdAt, :createdAt)
                        """)
                .param("objectKey", objectKey)
                .param("sizeBytes", bytes.length)
                .param("publicUrl", publicUrl)
                .param("createdAt", createdAt)
                .update();
        Long id = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new InsertedAsset(id, objectKey);
    }

    private InsertedAsset insertExpiredPublicReviewImage() {
        String objectKey = "public/library/image/review/" + UUID.randomUUID() + ".webp";
        String publicUrl = "https://cdn.example.test/" + objectKey;
        byte[] bytes = "review-image".getBytes();
        storageProvider.put(objectKey, "image/webp", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, public_url,
                             status, uploaded_by_type, uploaded_by_id, upload_context_type,
                             upload_context_id, expires_at)
                        values
                            ('LIBRARY', 'IMAGE', 'PUBLIC', 'TENCENT_COS', '', :objectKey,
                             'review.webp', 'image/webp', 'webp', :sizeBytes, '', :publicUrl,
                             'ACTIVE', 'APP', 992, 'PRODUCT_REVIEW_ORDER_ITEM', 991, :expiresAt)
                        """)
                .param("objectKey", objectKey)
                .param("sizeBytes", bytes.length)
                .param("publicUrl", publicUrl)
                .param("expiresAt", LocalDateTime.now().minusHours(1))
                .update();
        Long id = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new InsertedAsset(id, objectKey);
    }

    private String status(Long assetId) {
        return jdbcClient.sql("select status from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(String.class)
                .single();
    }

    private int cleanup() {
        return cleanupService.cleanupExpiredAssets(2, Duration.ofMinutes(30)).cleanedCount();
    }

    private String publicUrl(Long assetId) {
        return jdbcClient.sql("select public_url from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(String.class)
                .single();
    }

    private record InsertedAsset(Long id, String objectKey) {
    }

    private record CleanupRetry(int attempts, LocalDateTime nextRetryAt) {
    }
}
