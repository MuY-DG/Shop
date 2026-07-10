CREATE TABLE user_address (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    receiver_name VARCHAR(64) NOT NULL,
    receiver_phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_address_user_default
    ON user_address(user_id, is_default, id);

CREATE TABLE wechat_delivery_company (
    delivery_id VARCHAR(128) PRIMARY KEY,
    delivery_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    synced_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_wechat_delivery_company_enabled_name
    ON wechat_delivery_company(enabled, delivery_name);

ALTER TABLE shop_order
    ADD COLUMN checkout_request_digest VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    MODIFY COLUMN receiver_address VARCHAR(512) NOT NULL DEFAULT '';

ALTER TABLE order_shipment
    RENAME COLUMN express_company TO express_company_name;

ALTER TABLE order_shipment
    MODIFY COLUMN express_company_name VARCHAR(128) NULL;

ALTER TABLE order_shipment
    MODIFY COLUMN tracking_no VARCHAR(80) NULL;

ALTER TABLE order_shipment
    ADD COLUMN logistics_type INT NOT NULL DEFAULT 1;

ALTER TABLE order_shipment
    ADD COLUMN delivery_mode INT NOT NULL DEFAULT 1;

ALTER TABLE order_shipment
    ADD COLUMN item_desc VARCHAR(240) NOT NULL DEFAULT '历史订单商品';

ALTER TABLE order_shipment
    ADD COLUMN express_company_code VARCHAR(128) NULL;

ALTER TABLE order_shipment
    ADD COLUMN consignor_contact VARCHAR(128) NULL;

ALTER TABLE order_shipment
    ADD COLUMN receiver_contact VARCHAR(128) NULL;

ALTER TABLE order_shipment
    ADD COLUMN upload_time VARCHAR(64) NULL;

ALTER TABLE order_shipment
    ADD COLUMN last_attempt_at TIMESTAMP NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_provider_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

UPDATE order_shipment
SET item_desc = COALESCE(
    (
        SELECT SUBSTRING(MIN(order_item.product_title), 1, 120)
        FROM order_item
        WHERE order_item.order_id = order_shipment.order_id
    ),
    '历史订单商品'
)
WHERE item_desc = '历史订单商品';
