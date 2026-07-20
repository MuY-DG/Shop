ALTER TABLE payment_order
    ADD COLUMN notification_route_token VARCHAR(48) NULL;

CREATE UNIQUE INDEX uk_payment_order_notification_route
    ON payment_order(notification_route_token);

ALTER TABLE refund_order
    ADD COLUMN notification_route_token VARCHAR(48) NULL;

CREATE UNIQUE INDEX uk_refund_order_notification_route
    ON refund_order(notification_route_token);

ALTER TABLE payment_callback_log
    ADD COLUMN route_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY';

ALTER TABLE payment_callback_log
    ADD COLUMN route_digest VARCHAR(64) NOT NULL DEFAULT '';
