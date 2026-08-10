ALTER TABLE shop_order
    ADD COLUMN payment_expires_at TIMESTAMP NULL;

ALTER TABLE shop_order
    ADD COLUMN created_timeout_claim_token VARCHAR(36) NULL;

ALTER TABLE shop_order
    ADD COLUMN created_timeout_claimed_at TIMESTAMP NULL;

ALTER TABLE shop_order
    ADD COLUMN created_timeout_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX idx_shop_order_created_timeout
    ON shop_order(status, payment_expires_at, id);
