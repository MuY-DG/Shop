ALTER TABLE refund_order
    ADD COLUMN failed_at TIMESTAMP NULL;

UPDATE refund_order
SET failed_at = updated_at
WHERE status = 'FAILED'
  AND failed_at IS NULL;
