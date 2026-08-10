ALTER TABLE refund_order
    ADD COLUMN restock_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE refund_order
    ADD COLUMN restocked_at TIMESTAMP NULL;

ALTER TABLE stock_lock
    ADD COLUMN restock_refund_order_id BIGINT NULL;

ALTER TABLE stock_lock
    ADD COLUMN restocked_at TIMESTAMP NULL;

ALTER TABLE stock_log
    ADD COLUMN refund_order_id BIGINT NULL;

CREATE INDEX idx_stock_lock_restock_refund
    ON stock_lock(restock_refund_order_id, id);

CREATE UNIQUE INDEX uk_stock_log_refund_sku_change
    ON stock_log(refund_order_id, sku_id, change_type);
