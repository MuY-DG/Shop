CREATE TABLE order_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(20) NOT NULL DEFAULT '',
    to_status VARCHAR(20) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    operator_type VARCHAR(20) NOT NULL,
    operator_id BIGINT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_order_status_log_order_created
    ON order_status_log(order_id, created_at, id);

CREATE INDEX idx_shop_order_admin_status_created
    ON shop_order(status, created_at, id);

CREATE INDEX idx_shop_order_admin_receiver_phone
    ON shop_order(receiver_phone);

CREATE INDEX idx_order_shipment_admin_tracking_no
    ON order_shipment(tracking_no);

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT id, '', 'CREATED', 'ORDER_CREATED', 'SYSTEM', '历史订单创建', created_at
FROM shop_order;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT order_id, 'CREATED', 'PAYING', 'PAYMENT_STARTED', 'SYSTEM', '历史订单发起支付', created_at
FROM payment_order;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT order_id, 'PAYING', 'PAID', 'PAYMENT_SUCCEEDED', 'SYSTEM', '历史订单支付成功', paid_at
FROM payment_order
WHERE status = 'PAID' AND paid_at IS NOT NULL;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT order_id, 'PAID', 'SHIPPED', 'ORDER_SHIPPED', 'SYSTEM', '历史订单已发货', shipped_at
FROM order_shipment
WHERE shipped_at IS NOT NULL;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT id, 'SHIPPED', 'COMPLETED', 'ORDER_COMPLETED', 'SYSTEM', '历史订单已完成', completed_at
FROM shop_order
WHERE completed_at IS NOT NULL;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT o.id,
       CASE WHEN EXISTS (
           SELECT 1 FROM payment_order p WHERE p.order_id = o.id
       ) THEN 'PAYING' ELSE 'CREATED' END,
       'CLOSED', 'ORDER_CLOSED', 'SYSTEM', '历史订单已关闭', o.closed_at
FROM shop_order o
WHERE o.closed_at IS NOT NULL;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT id,
       CASE
           WHEN completed_at IS NOT NULL THEN 'COMPLETED'
           WHEN shipped_at IS NOT NULL THEN 'SHIPPED'
           ELSE 'PAID'
       END,
       'REFUNDING', 'REFUND_STARTED', 'SYSTEM', '历史订单开始退款', refunding_at
FROM shop_order
WHERE refunding_at IS NOT NULL;

INSERT INTO order_status_log (
    order_id, from_status, to_status, event_type, operator_type, description, created_at
)
SELECT id, 'REFUNDING', 'REFUNDED', 'REFUND_SUCCEEDED', 'SYSTEM', '历史订单退款成功', refunded_at
FROM shop_order
WHERE refunded_at IS NOT NULL;
