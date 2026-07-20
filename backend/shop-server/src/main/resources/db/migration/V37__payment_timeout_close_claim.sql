ALTER TABLE payment_order
    ADD COLUMN timeout_close_claim_token VARCHAR(36) NULL;

ALTER TABLE payment_order
    ADD COLUMN timeout_close_claimed_at TIMESTAMP NULL;

ALTER TABLE payment_order
    ADD COLUMN timeout_close_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX idx_payment_order_timeout_close
    ON payment_order(status, expires_at, id);
