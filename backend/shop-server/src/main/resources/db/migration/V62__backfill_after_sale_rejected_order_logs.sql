INSERT INTO order_status_log (
    order_id,
    from_status,
    to_status,
    event_type,
    operator_type,
    operator_id,
    description,
    created_at
)
SELECT
    asr.order_id,
    CASE
        WHEN o.completed_at IS NOT NULL AND o.completed_at <= asr.reviewed_at THEN 'COMPLETED'
        WHEN o.shipped_at IS NOT NULL AND o.shipped_at <= asr.reviewed_at THEN 'SHIPPED'
        ELSE 'PAID'
    END,
    CASE
        WHEN o.completed_at IS NOT NULL AND o.completed_at <= asr.reviewed_at THEN 'COMPLETED'
        WHEN o.shipped_at IS NOT NULL AND o.shipped_at <= asr.reviewed_at THEN 'SHIPPED'
        ELSE 'PAID'
    END,
    'AFTER_SALE_REJECTED',
    'ADMIN',
    asr.reviewed_by,
    '售后审核拒绝',
    asr.reviewed_at
FROM after_sale_request asr
JOIN shop_order o ON o.id = asr.order_id
WHERE asr.status = 'REJECTED'
  AND asr.reviewed_at IS NOT NULL;
