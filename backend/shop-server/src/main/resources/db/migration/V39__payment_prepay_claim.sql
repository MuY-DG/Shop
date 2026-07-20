ALTER TABLE payment_order
    ADD COLUMN prepay_claim_token VARCHAR(36) NULL;

ALTER TABLE payment_order
    ADD COLUMN prepay_claimed_at TIMESTAMP NULL;

ALTER TABLE payment_order
    ADD COLUMN prepay_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX idx_payment_order_prepay_claim
    ON payment_order(order_id, status, prepay_claimed_at, id);
