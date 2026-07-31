package org.muybaby.shopserver.customerservice.service;

import net.coobird.thumbnailator.Thumbnails;
import org.muybaby.shopserver.customerservice.CustomerServiceChangedEvent;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class CustomerServiceImageThumbnailService {

    private static final Logger log =
            LoggerFactory.getLogger(CustomerServiceImageThumbnailService.class);
    private static final String CUSTOMER_SERVICE_CONTEXT = "CUSTOMER_SERVICE_CONVERSATION";
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbcClient;
    private final StorageProvider storageProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerServiceThumbnailEncoder thumbnailEncoder;
    private final int maxDimension;
    private final int batchSize;
    private final Duration processingTimeout;

    public CustomerServiceImageThumbnailService(
            JdbcClient jdbcClient,
            StorageProvider storageProvider,
            ApplicationEventPublisher eventPublisher,
            CustomerServiceThumbnailEncoder thumbnailEncoder,
            @Value("${shop.storage.customer-service-thumbnail.max-dimension:720}") int maxDimension,
            @Value("${shop.storage.customer-service-thumbnail.batch-size:20}") int batchSize,
            @Value("${shop.storage.customer-service-thumbnail.processing-timeout:5m}")
            Duration processingTimeout
    ) {
        this.jdbcClient = jdbcClient;
        this.storageProvider = storageProvider;
        this.eventPublisher = eventPublisher;
        this.thumbnailEncoder = thumbnailEncoder;
        this.maxDimension = Math.max(64, Math.min(maxDimension, 2048));
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.processingTimeout = positiveDuration(processingTimeout, Duration.ofMinutes(5));
    }

    public int processPendingThumbnails() {
        List<Long> assetIds = jdbcClient.sql("""
                        select asset.id
                        from storage_asset asset
                        where asset.scope = 'ATTACHMENT'
                          and asset.media_kind = 'IMAGE'
                          and asset.status = 'ACTIVE'
                          and asset.upload_context_type = :contextType
                          and asset.expires_at is null
                          and (
                              asset.thumbnail_status = 'PENDING'
                              or (
                                  asset.thumbnail_status = 'FAILED'
                                  and (
                                      asset.thumbnail_next_retry_at is null
                                      or asset.thumbnail_next_retry_at <= current_timestamp
                                  )
                              )
                              or (
                                  asset.thumbnail_status = 'PROCESSING'
                                  and asset.thumbnail_started_at <= :staleBefore
                              )
                          )
                          and exists (
                              select 1
                              from customer_service_message message
                              where message.message_type = 'IMAGE'
                                and message.resource_id = asset.id
                          )
                        order by asset.id
                        limit :limit
                        """)
                .param("contextType", CUSTOMER_SERVICE_CONTEXT)
                .param("staleBefore", databaseNow().minus(processingTimeout))
                .param("limit", batchSize)
                .query(Long.class)
                .list();
        int completed = 0;
        for (Long assetId : assetIds) {
            if (generate(assetId)) {
                completed++;
            }
        }
        return completed;
    }

    public boolean generate(Long assetId) {
        ThumbnailSource source = claim(assetId);
        if (source == null) {
            return false;
        }
        if ("image/svg+xml".equalsIgnoreCase(source.contentType())) {
            markUnavailable(source.id(), "SVG thumbnails require rasterization support");
            return false;
        }

        StorageObjectLocation thumbnailLocation = null;
        try {
            CustomerServiceThumbnailEncoder.EncodedThumbnail output = render(source);
            thumbnailLocation = source.thumbnailLocation(
                    thumbnailObjectKey(source.objectKey(), output.extension()));
            storageProvider.put(
                    thumbnailLocation,
                    output.contentType(),
                    new ByteArrayInputStream(output.bytes()),
                    output.bytes().length
            );
            int updated = jdbcClient.sql("""
                            update storage_asset
                            set thumbnail_status = 'READY',
                                thumbnail_object_key = :objectKey,
                                thumbnail_content_type = :contentType,
                                thumbnail_size_bytes = :sizeBytes,
                                thumbnail_sha256 = :sha256,
                                thumbnail_width = :width,
                                thumbnail_height = :height,
                                thumbnail_started_at = null,
                                thumbnail_next_retry_at = null,
                                updated_at = current_timestamp
                            where id = :assetId
                              and status = 'ACTIVE'
                              and thumbnail_status = 'PROCESSING'
                            """)
                    .param("objectKey", thumbnailLocation.objectKey())
                    .param("contentType", output.contentType())
                    .param("sizeBytes", output.bytes().length)
                    .param("sha256", sha256(output.bytes()))
                    .param("width", output.width())
                    .param("height", output.height())
                    .param("assetId", source.id())
                    .update();
            if (updated != 1) {
                deleteQuietly(thumbnailLocation);
                return false;
            }
            publishChanged(source.id(), "IMAGE_THUMBNAIL_READY");
            return true;
        } catch (UnsupportedThumbnailException ex) {
            if (thumbnailLocation != null) {
                deleteQuietly(thumbnailLocation);
            }
            markUnavailable(source.id(), ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            scheduleRetry(source, ex);
            return false;
        } catch (LinkageError ex) {
            markUnavailable(source.id(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private ThumbnailSource claim(Long assetId) {
        if (assetId == null || assetId <= 0) {
            return null;
        }
        LocalDateTime now = databaseNow();
        int claimed = jdbcClient.sql("""
                        update storage_asset
                        set thumbnail_status = 'PROCESSING',
                            thumbnail_attempts = thumbnail_attempts + 1,
                            thumbnail_started_at = :now,
                            thumbnail_next_retry_at = null,
                            updated_at = current_timestamp
                        where id = :assetId
                          and scope = 'ATTACHMENT'
                          and media_kind = 'IMAGE'
                          and status = 'ACTIVE'
                          and upload_context_type = :contextType
                          and expires_at is null
                          and (
                              thumbnail_status = 'PENDING'
                              or (
                                  thumbnail_status = 'FAILED'
                                  and (
                                      thumbnail_next_retry_at is null
                                      or thumbnail_next_retry_at <= :now
                                  )
                              )
                              or (
                                  thumbnail_status = 'PROCESSING'
                                  and thumbnail_started_at <= :staleBefore
                              )
                          )
                          and exists (
                              select 1
                              from customer_service_message message
                              where message.message_type = 'IMAGE'
                                and message.resource_id = storage_asset.id
                          )
                        """)
                .param("assetId", assetId)
                .param("contextType", CUSTOMER_SERVICE_CONTEXT)
                .param("now", now)
                .param("staleBefore", now.minus(processingTimeout))
                .update();
        if (claimed != 1) {
            return null;
        }
        return jdbcClient.sql("""
                        select id, provider, storage_container, storage_region, object_key,
                               content_type, width, height, thumbnail_attempts
                        from storage_asset
                        where id = :assetId
                          and thumbnail_status = 'PROCESSING'
                        """)
                .param("assetId", assetId)
                .query((rs, rowNum) -> new ThumbnailSource(
                        rs.getLong("id"),
                        StorageProviderKind.valueOf(rs.getString("provider")),
                        rs.getString("storage_container"),
                        rs.getString("storage_region"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getObject("width", Integer.class),
                        rs.getObject("height", Integer.class),
                        rs.getInt("thumbnail_attempts")
                ))
                .optional()
                .orElse(null);
    }

    private CustomerServiceThumbnailEncoder.EncodedThumbnail render(ThumbnailSource source) {
        try {
            StoredObject stored = storageProvider.open(source.originalLocation());
            BufferedImage thumbnail;
            try (InputStream input = stored.inputStream()) {
                double scale = scale(source.width(), source.height());
                thumbnail = Thumbnails.of(input)
                        .scale(scale)
                        .useExifOrientation(true)
                        .asBufferedImage();
            }
            if (thumbnail == null || thumbnail.getWidth() <= 0 || thumbnail.getHeight() <= 0) {
                throw new UnsupportedThumbnailException("Image decoder returned no thumbnail");
            }
            return thumbnailEncoder.encode(thumbnail);
        } catch (UnsupportedThumbnailException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException | LinkageError ex) {
            throw new UnsupportedThumbnailException("Unsupported thumbnail source", ex);
        }
    }

    private double scale(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return 1.0d;
        }
        return Math.min(1.0d, (double) maxDimension / Math.max(width, height));
    }

    private void scheduleRetry(ThumbnailSource source, RuntimeException failure) {
        if (source.attempts() >= MAX_ATTEMPTS) {
            markUnavailable(source.id(), failure.getClass().getSimpleName());
            return;
        }
        long retryMinutes = 1L << Math.min(Math.max(source.attempts() - 1, 0), 6);
        LocalDateTime retryAt = databaseNow().plusMinutes(retryMinutes);
        jdbcClient.sql("""
                        update storage_asset
                        set thumbnail_status = 'FAILED',
                            thumbnail_started_at = null,
                            thumbnail_next_retry_at = :retryAt,
                            updated_at = current_timestamp
                        where id = :assetId
                          and thumbnail_status = 'PROCESSING'
                        """)
                .param("retryAt", retryAt)
                .param("assetId", source.id())
                .update();
        log.warn(
                "Customer-service thumbnail generation failed; retry scheduled: assetId={}, attempts={}, retryAt={}, exception={}",
                source.id(), source.attempts(), retryAt, failure.getClass().getSimpleName()
        );
    }

    private void markUnavailable(Long assetId, String reason) {
        int updated = jdbcClient.sql("""
                        update storage_asset
                        set thumbnail_status = 'UNAVAILABLE',
                            thumbnail_started_at = null,
                            thumbnail_next_retry_at = null,
                            updated_at = current_timestamp
                        where id = :assetId
                          and thumbnail_status = 'PROCESSING'
                        """)
                .param("assetId", assetId)
                .update();
        if (updated == 1) {
            publishChanged(assetId, "IMAGE_THUMBNAIL_UNAVAILABLE");
        }
        log.info(
                "Customer-service thumbnail is unavailable; original image remains accessible: assetId={}, reason={}",
                assetId, reason
        );
    }

    private void publishChanged(Long assetId, String reason) {
        try {
            jdbcClient.sql("""
                            select message.id as message_id,
                                   conversation.id as conversation_id,
                                   conversation.app_user_id
                            from customer_service_message message
                            join customer_service_conversation conversation
                              on conversation.id = message.conversation_id
                            where message.message_type = 'IMAGE'
                              and message.resource_id = :assetId
                            order by message.id
                            limit 1
                            """)
                    .param("assetId", assetId)
                    .query((rs, rowNum) -> new ThumbnailMessage(
                            rs.getLong("message_id"),
                            rs.getLong("conversation_id"),
                            rs.getLong("app_user_id")
                    ))
                    .optional()
                    .ifPresent(message -> eventPublisher.publishEvent(
                            new CustomerServiceChangedEvent(
                                    message.conversationId(),
                                    message.appUserId(),
                                    reason,
                                    message.messageId()
                            )));
        } catch (RuntimeException ex) {
            log.warn(
                    "Customer-service thumbnail state changed but realtime notification failed: assetId={}, reason={}, exception={}",
                    assetId, reason, ex.getClass().getSimpleName()
            );
        }
    }

    private void deleteQuietly(StorageObjectLocation location) {
        try {
            storageProvider.delete(location);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to remove an unreferenced customer-service thumbnail: objectKey={}, exception={}",
                    location.objectKey(), ex.getClass().getSimpleName()
            );
        }
    }

    private String thumbnailObjectKey(String originalKey, String extension) {
        int slash = originalKey.lastIndexOf('/');
        int dot = originalKey.lastIndexOf('.');
        String base = dot > slash ? originalKey.substring(0, dot) : originalKey;
        return base + ".thumb-" + maxDimension + "." + extension;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private record ThumbnailSource(
            Long id,
            StorageProviderKind provider,
            String storageContainer,
            String storageRegion,
            String objectKey,
            String contentType,
            Integer width,
            Integer height,
            int attempts
    ) {
        private StorageObjectLocation originalLocation() {
            return new StorageObjectLocation(
                    provider, storageContainer, storageRegion, objectKey);
        }

        private StorageObjectLocation thumbnailLocation(String thumbnailObjectKey) {
            return new StorageObjectLocation(
                    provider, storageContainer, storageRegion, thumbnailObjectKey);
        }
    }

    private record ThumbnailMessage(Long messageId, Long conversationId, Long appUserId) {
    }

    private static final class UnsupportedThumbnailException extends RuntimeException {
        private UnsupportedThumbnailException(String message) {
            super(message);
        }

        private UnsupportedThumbnailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
