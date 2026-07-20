ALTER TABLE refund_order
    ADD COLUMN recovery_claim_token VARCHAR(36) NULL;

ALTER TABLE refund_order
    ADD COLUMN recovery_claimed_at TIMESTAMP NULL;

ALTER TABLE refund_order
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0;

ALTER TABLE refund_order
    ADD COLUMN next_recovery_at TIMESTAMP NULL;

CREATE INDEX idx_refund_order_recovery
    ON refund_order(status, next_recovery_at, id);
