UPDATE storage_asset
SET thumbnail_status = 'PENDING',
    thumbnail_attempts = 0,
    thumbnail_started_at = NULL,
    thumbnail_next_retry_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE scope = 'ATTACHMENT'
  AND media_kind = 'IMAGE'
  AND status = 'ACTIVE'
  AND upload_context_type = 'CUSTOMER_SERVICE_CONVERSATION'
  AND expires_at IS NULL
  AND thumbnail_status IN ('PENDING', 'PROCESSING', 'FAILED', 'UNAVAILABLE')
  AND EXISTS (
      SELECT 1
      FROM customer_service_message message
      WHERE message.message_type = 'IMAGE'
        AND message.resource_id = storage_asset.id
  );
