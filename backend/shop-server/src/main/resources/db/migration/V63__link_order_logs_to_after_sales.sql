ALTER TABLE order_status_log
    ADD COLUMN after_sale_id BIGINT NULL;

CREATE INDEX idx_order_status_log_after_sale_created
    ON order_status_log(after_sale_id, created_at, id);

UPDATE order_status_log osl
SET after_sale_id = (
    SELECT MAX(asr.id)
    FROM after_sale_request asr
    WHERE asr.order_id = osl.order_id
      AND asr.created_at <= osl.created_at
)
WHERE (
        osl.event_type LIKE 'AFTER_SALE_%'
        OR osl.event_type LIKE 'REFUND_%'
    )
  AND EXISTS (
      SELECT 1
      FROM after_sale_request asr
      WHERE asr.order_id = osl.order_id
        AND asr.created_at <= osl.created_at
  );
