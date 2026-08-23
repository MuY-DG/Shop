CREATE TABLE finance_external_refund (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    difference_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    payment_order_id BIGINT NOT NULL,
    provider_identity_key CHAR(64) NOT NULL,
    mch_id VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    out_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    out_refund_no VARCHAR(64) NOT NULL DEFAULT '',
    amount_cent BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    row_digest CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    recorded_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_external_refund_difference UNIQUE (difference_id),
    CONSTRAINT uk_finance_external_refund_identity UNIQUE (provider_identity_key),
    CONSTRAINT fk_finance_external_refund_difference FOREIGN KEY (difference_id)
        REFERENCES finance_reconciliation_difference(id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_external_refund_batch FOREIGN KEY (batch_id)
        REFERENCES finance_reconciliation_batch(id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_external_refund_order FOREIGN KEY (order_id)
        REFERENCES shop_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_external_refund_payment FOREIGN KEY (payment_order_id)
        REFERENCES payment_order(id) ON DELETE RESTRICT,
    CONSTRAINT chk_finance_external_refund_amount CHECK (amount_cent > 0),
    CONSTRAINT chk_finance_external_refund_currency CHECK (currency = 'CNY'),
    CONSTRAINT chk_finance_external_refund_status CHECK (provider_status = 'SUCCESS'),
    CONSTRAINT chk_finance_external_refund_identity_present CHECK (
        refund_id <> '' OR out_refund_no <> ''
    )
);

CREATE INDEX idx_finance_external_refund_order
    ON finance_external_refund(order_id, occurred_at, id);

CREATE INDEX idx_finance_external_refund_payment
    ON finance_external_refund(payment_order_id, occurred_at, id);

ALTER TABLE finance_reconciliation_difference
    ADD COLUMN external_refund_applied BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_finance_reconciliation_external_refund
    ON finance_reconciliation_difference(external_refund_applied, status, id);
