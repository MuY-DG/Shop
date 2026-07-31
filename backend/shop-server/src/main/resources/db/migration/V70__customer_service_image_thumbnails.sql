ALTER TABLE storage_asset
    ADD COLUMN thumbnail_status VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_object_key VARCHAR(255) NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_content_type VARCHAR(128) NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_size_bytes BIGINT NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_sha256 VARCHAR(64) NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_width INT NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_height INT NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_started_at TIMESTAMP NULL;
ALTER TABLE storage_asset
    ADD COLUMN thumbnail_next_retry_at TIMESTAMP NULL;
ALTER TABLE storage_asset
    ADD CONSTRAINT chk_storage_asset_thumbnail_status CHECK (
        thumbnail_status IN ('NONE', 'PENDING', 'PROCESSING', 'READY', 'FAILED', 'UNAVAILABLE')
    );
CREATE INDEX idx_storage_asset_thumbnail_work
    ON storage_asset(upload_context_type, thumbnail_status, thumbnail_next_retry_at, id);

UPDATE storage_asset
SET thumbnail_status = 'PENDING'
WHERE storage_asset.scope = 'ATTACHMENT'
  AND storage_asset.media_kind = 'IMAGE'
  AND storage_asset.status = 'ACTIVE'
  AND storage_asset.upload_context_type = 'CUSTOMER_SERVICE_CONVERSATION'
  AND storage_asset.expires_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM customer_service_message message
      WHERE message.message_type = 'IMAGE'
        AND message.resource_id = storage_asset.id
  );
