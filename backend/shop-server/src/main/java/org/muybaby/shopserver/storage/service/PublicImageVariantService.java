package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PublicImageVariantService {
    private static final Logger log = LoggerFactory.getLogger(PublicImageVariantService.class);
    private static final String ELIGIBLE = """
            scope = 'LIBRARY' and media_kind = 'IMAGE' and visibility = 'PUBLIC'
            and uploaded_by_type = 'ADMIN' and provider = 'TENCENT_COS'
            and content_type in ('image/webp', 'image/jpeg', 'image/png', 'image/gif')
            and status = 'ACTIVE' and public_image_variants_ready = false
            and (public_image_variants_retry_at is null or public_image_variants_retry_at <= current_timestamp)
            """;
    private final JdbcClient jdbc;
    private final StorageProvider storage;
    private final TransactionTemplate transaction;

    public PublicImageVariantService(JdbcClient jdbc, StorageProvider storage, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A single batched read; never performs COS work in a product request. */
    public Map<String, PublicImageVariants.Urls> findByUrls(Collection<String> urls) {
        List<String> values = urls == null
                ? List.of()
                : urls.stream().filter(url -> url != null && !url.isBlank()).distinct().toList();
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<String, PublicImageVariants.Urls> result = new LinkedHashMap<>();
        jdbc.sql("""
                        select public_url from storage_asset
                        where public_url in (:urls) and scope = 'LIBRARY' and media_kind = 'IMAGE'
                          and visibility = 'PUBLIC' and uploaded_by_type = 'ADMIN'
                          and provider = 'TENCENT_COS'
                          and content_type in ('image/webp', 'image/jpeg', 'image/png', 'image/gif')
                          and status = 'ACTIVE'
                          and public_image_variants_ready = true
                        """)
                .param("urls", values).query(String.class).list()
                .forEach(url -> result.put(url, PublicImageVariants.urls(url)));
        return result;
    }

    public int backfillBatch(int limit) {
        List<Long> ids = jdbc.sql("select id from storage_asset where " + ELIGIBLE + " order by id limit :limit")
                .param("limit", Math.max(1, Math.min(limit, 10))).query(Long.class).list();
        int completed = 0;
        for (Long id : ids) {
            if (generate(id)) {
                completed++;
            }
        }
        return completed;
    }

    public boolean generate(Long assetId) {
        // One asset per transaction. The row lock serializes backfill with asset deletion and
        // other backfill workers, so cleanup cannot finish before a late COS output is written.
        return Boolean.TRUE.equals(transaction.execute(status -> {
            StorageObjectLocation source = jdbc.sql("""
                            select storage_container, storage_region, object_key from storage_asset
                            where id = :id and %s for update
                            """.formatted(ELIGIBLE))
                    .param("id", assetId).query((rs, row) -> new StorageObjectLocation(
                            StorageProviderKind.TENCENT_COS, rs.getString("storage_container"),
                            rs.getString("storage_region"), rs.getString("object_key"))).optional().orElse(null);
            if (source == null) {
                return false;
            }
            try {
                var images = storage.processImage(source, PublicImageVariants.outputs(source.objectKey()));
                PublicImageVariants.validate(source.objectKey(), images);
                jdbc.sql("""
                        update storage_asset set public_image_variants_ready = true,
                            public_image_variants_retry_at = null where id = :id
                        """).param("id", assetId).update();
                return true;
            } catch (RuntimeException ex) {
                // Deterministic output keys remain attached to this asset even after partial failure;
                // retries overwrite them and asset cleanup always deletes both, ready or not.
                LocalDateTime retryAt = jdbc.sql("select current_timestamp").query(LocalDateTime.class)
                        .single().plusHours(1);
                jdbc.sql("update storage_asset set public_image_variants_retry_at = :retryAt where id = :id")
                        .param("id", assetId).param("retryAt", retryAt).update();
                log.warn(
                        "Public image variants will retry: assetId={}, exception={}",
                        assetId,
                        ex.getClass().getSimpleName()
                );
                return false;
            }
        }));
    }
}
