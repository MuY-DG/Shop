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

@SpringBootTest(properties = "shop.storage.cleanup.batch-size=2")
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

        assertThat(cleanupService.cleanupExpiredAssets()).isEqualTo(1);

        assertThat(status(expired.id())).isEqualTo("DELETED");
        assertThat(status(referenced.id())).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> storageProvider.open(expired.objectKey())).isInstanceOf(RuntimeException.class);

        jdbcClient.sql("update storage_asset_usage set status = 'REMOVED' where asset_id = :assetId")
                .param("assetId", referenced.id())
                .update();
        assertThat(cleanupService.cleanupExpiredAssets()).isEqualTo(1);
        assertThat(status(referenced.id())).isEqualTo("DELETED");
    }

    @Test
    void failedProviderDeleteUsesABackoffLeaseWithoutBlockingNewExpiredAssets() {
        long failedAssetId = insertFailedCosRetry();
        long secondFailedAssetId = insertFailedCosRetry();
        InsertedAsset expired = insertExpiredAsset("SECRET");

        assertThat(cleanupService.cleanupExpiredAssets()).isEqualTo(1);
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
                .single()).isEqualTo(1);

        assertThat(cleanupService.cleanupExpiredAssets()).isZero();
        assertThat(jdbcClient.sql("select cleanup_attempts from storage_asset where id = :assetId")
                .param("assetId", failedAssetId)
                .query(Integer.class)
                .single()).isEqualTo(1);
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
                transactionTemplate,
                2
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = executor.submit(firstWorker::cleanupExpiredAssets);
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
                            (:scope, 'DOCUMENT', 'PRIVATE', 'LOCAL', '', :objectKey,
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

    private String status(Long assetId) {
        return jdbcClient.sql("select status from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(String.class)
                .single();
    }

    private record InsertedAsset(Long id, String objectKey) {
    }

    private record CleanupRetry(int attempts, LocalDateTime nextRetryAt) {
    }
}
