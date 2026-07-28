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
        WHEN o.completed_at IS NOT NULL AND o.completed_at <= asr.created_at THEN 'COMPLETED'
        WHEN o.shipped_at IS NOT NULL AND o.shipped_at <= asr.created_at THEN 'SHIPPED'
        ELSE 'PAID'
    END,
    CASE
        WHEN o.completed_at IS NOT NULL AND o.completed_at <= asr.created_at THEN 'COMPLETED'
        WHEN o.shipped_at IS NOT NULL AND o.shipped_at <= asr.created_at THEN 'SHIPPED'
        ELSE 'PAID'
    END,
    'AFTER_SALE_REQUESTED',
    'APP',
    asr.user_id,
    '用户申请售后',
    asr.created_at
FROM after_sale_request asr
JOIN shop_order o ON o.id = asr.order_id;
