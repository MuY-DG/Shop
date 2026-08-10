ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_claim_token VARCHAR(36) NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_claimed_at TIMESTAMP NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_next_action_at TIMESTAMP NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_attempt_count INT NOT NULL DEFAULT 0;

ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_not_uploaded_observations INT NOT NULL DEFAULT 0;

ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_last_reconciled_at TIMESTAMP NULL;

CREATE INDEX idx_order_shipment_wechat_delivery_due
    ON order_shipment(wechat_upload_status, wechat_upload_next_action_at, id);

UPDATE order_shipment
SET wechat_upload_status = 'UNKNOWN',
    wechat_error_code = 'LEGACY_ATTEMPT_OUTCOME_UNKNOWN',
    wechat_error_message = 'Previous WeChat shipping attempt outcome requires reconciliation',
    wechat_upload_next_action_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE wechat_upload_status = 'UPLOADING';

UPDATE order_shipment
SET wechat_upload_status = 'PENDING',
    wechat_upload_next_action_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE wechat_upload_status = 'SKIPPED'
  AND wechat_provider_mode IN ('REAL', 'UNKNOWN')
  AND order_id IN (
      SELECT id
      FROM shop_order
      WHERE status = 'SHIPPED'
  );
