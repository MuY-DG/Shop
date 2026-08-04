ALTER TABLE order_shipment
    ADD COLUMN wechat_receipt_claim_token VARCHAR(36) NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_receipt_claimed_at TIMESTAMP NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_receipt_last_checked_at TIMESTAMP NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_receipt_order_state INT NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_receipt_last_error_code VARCHAR(64) NOT NULL DEFAULT '';

CREATE INDEX idx_order_shipment_receipt_reconciliation
    ON order_shipment(wechat_upload_status, wechat_receipt_last_checked_at);
