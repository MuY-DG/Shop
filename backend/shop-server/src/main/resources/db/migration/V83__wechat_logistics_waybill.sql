ALTER TABLE shop_order
    ADD COLUMN receiver_province VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    ADD COLUMN receiver_city VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    ADD COLUMN receiver_district VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    ADD COLUMN receiver_detail_address VARCHAR(512) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    ADD COLUMN receiver_location_name VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    ADD COLUMN receiver_doorplate VARCHAR(128) NOT NULL DEFAULT '';

CREATE TABLE wechat_express_setting (
    id BIGINT PRIMARY KEY,
    mode VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
    message_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sender_name VARCHAR(64) NOT NULL DEFAULT '',
    sender_mobile VARCHAR(32) NOT NULL DEFAULT '',
    sender_company VARCHAR(128) NOT NULL DEFAULT '',
    sender_province VARCHAR(64) NOT NULL DEFAULT '',
    sender_city VARCHAR(64) NOT NULL DEFAULT '',
    sender_district VARCHAR(64) NOT NULL DEFAULT '',
    sender_detail_address VARCHAR(512) NOT NULL DEFAULT '',
    delivery_id VARCHAR(128) NOT NULL DEFAULT '',
    delivery_name VARCHAR(128) NOT NULL DEFAULT '',
    biz_id VARCHAR(128) NOT NULL DEFAULT '',
    service_type INT NULL,
    service_name VARCHAR(128) NOT NULL DEFAULT '',
    default_weight_kg DECIMAL(10, 3) NOT NULL DEFAULT 1.000,
    default_length_cm DECIMAL(10, 2) NOT NULL DEFAULT 20.00,
    default_width_cm DECIMAL(10, 2) NOT NULL DEFAULT 15.00,
    default_height_cm DECIMAL(10, 2) NOT NULL DEFAULT 10.00,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wechat_express_setting_singleton CHECK (id = 1),
    CONSTRAINT chk_wechat_express_setting_mode CHECK (
        mode IN ('DISABLED', 'SANDBOX', 'PRODUCTION')
    ),
    CONSTRAINT chk_wechat_express_setting_service_type CHECK (service_type >= 0),
    CONSTRAINT chk_wechat_express_setting_parcel CHECK (
        default_weight_kg > 0
        AND default_length_cm > 0
        AND default_width_cm > 0
        AND default_height_cm > 0
    ),
    CONSTRAINT chk_wechat_express_setting_revision CHECK (revision >= 0)
);

INSERT INTO wechat_express_setting (id)
VALUES (1);

CREATE TABLE order_electronic_waybill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    delivery_id VARCHAR(128) NOT NULL,
    delivery_name VARCHAR(128) NOT NULL,
    biz_id VARCHAR(128) NOT NULL DEFAULT '',
    service_type INT NOT NULL DEFAULT 0,
    service_name VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    pending_operation VARCHAR(16) NOT NULL DEFAULT 'NONE',
    waybill_id VARCHAR(128) NOT NULL DEFAULT '',
    parcel_count INT NOT NULL DEFAULT 1,
    weight_kg DECIMAL(10, 3) NOT NULL,
    length_cm DECIMAL(10, 2) NOT NULL,
    width_cm DECIMAL(10, 2) NOT NULL,
    height_cm DECIMAL(10, 2) NOT NULL,
    custom_remark VARCHAR(1024) NOT NULL DEFAULT '',
    expected_pickup_time BIGINT NULL,
    sender_name VARCHAR(64) NOT NULL,
    sender_mobile VARCHAR(32) NOT NULL,
    sender_company VARCHAR(128) NOT NULL DEFAULT '',
    sender_province VARCHAR(64) NOT NULL,
    sender_city VARCHAR(64) NOT NULL,
    sender_district VARCHAR(64) NOT NULL,
    sender_detail_address VARCHAR(512) NOT NULL,
    receiver_name VARCHAR(64) NOT NULL,
    receiver_phone VARCHAR(32) NOT NULL,
    receiver_province VARCHAR(64) NOT NULL,
    receiver_city VARCHAR(64) NOT NULL,
    receiver_district VARCHAR(64) NOT NULL,
    receiver_detail_address VARCHAR(512) NOT NULL,
    receiver_location_name VARCHAR(128) NOT NULL DEFAULT '',
    receiver_doorplate VARCHAR(128) NOT NULL DEFAULT '',
    payment_order_id BIGINT NOT NULL,
    payer_openid VARCHAR(128) NOT NULL,
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    upstream_attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP NULL,
    print_request_count INT NOT NULL DEFAULT 0,
    last_print_requested_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    confirmed_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    canceled_at TIMESTAMP NULL,
    confirmed_at TIMESTAMP NULL,
    CONSTRAINT chk_order_electronic_waybill_attempt CHECK (attempt_no > 0),
    CONSTRAINT chk_order_electronic_waybill_mode CHECK (
        mode IN ('SANDBOX', 'PRODUCTION')
    ),
    CONSTRAINT chk_order_electronic_waybill_status CHECK (
        status IN (
            'CREATING', 'CREATED', 'CANCELING', 'CANCELED',
            'UNKNOWN', 'FAILED', 'CONFIRMED'
        )
    ),
    CONSTRAINT chk_order_electronic_waybill_pending_operation CHECK (
        pending_operation IN ('NONE', 'CREATE', 'CANCEL', 'REFRESH')
    ),
    CONSTRAINT chk_order_electronic_waybill_parcel CHECK (
        parcel_count = 1
        AND weight_kg > 0
        AND length_cm > 0
        AND width_cm > 0
        AND height_cm > 0
    ),
    CONSTRAINT chk_order_electronic_waybill_service_type CHECK (service_type >= 0),
    CONSTRAINT chk_order_electronic_waybill_counts CHECK (
        upstream_attempt_count >= 0 AND print_request_count >= 0
    )
);

CREATE UNIQUE INDEX uk_order_electronic_waybill_provider_order
    ON order_electronic_waybill(provider_order_id);

CREATE UNIQUE INDEX uk_order_electronic_waybill_attempt
    ON order_electronic_waybill(order_id, attempt_no);

CREATE UNIQUE INDEX uk_order_electronic_waybill_idempotency
    ON order_electronic_waybill(order_id, idempotency_key);

CREATE INDEX idx_order_electronic_waybill_order_status
    ON order_electronic_waybill(order_id, status, id);

CREATE INDEX idx_order_electronic_waybill_status_attempt
    ON order_electronic_waybill(status, last_attempt_at, id);

ALTER TABLE order_shipment
    ADD COLUMN shipment_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE order_shipment
    ADD COLUMN electronic_waybill_id BIGINT NULL;

ALTER TABLE order_shipment
    ADD CONSTRAINT chk_order_shipment_source CHECK (
        shipment_source IN ('MANUAL', 'WECHAT_WAYBILL')
    );

CREATE UNIQUE INDEX uk_order_shipment_electronic_waybill
    ON order_shipment(electronic_waybill_id);

CREATE TABLE shipment_waybill_registration (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    registration_kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    waybill_token VARCHAR(1024) NOT NULL DEFAULT '',
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    claim_token VARCHAR(36) NULL,
    claimed_at TIMESTAMP NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP NULL,
    registered_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_shipment_waybill_registration_kind CHECK (
        registration_kind IN ('TRACE', 'FOLLOW')
    ),
    CONSTRAINT chk_shipment_waybill_registration_status CHECK (
        status IN (
            'PENDING', 'REGISTERING', 'REGISTERED', 'FAILED',
            'UNKNOWN', 'UNAVAILABLE', 'SKIPPED'
        )
    ),
    CONSTRAINT chk_shipment_waybill_registration_attempt CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX uk_shipment_waybill_registration_shipment
    ON shipment_waybill_registration(shipment_id);

CREATE INDEX idx_shipment_waybill_registration_status_claim
    ON shipment_waybill_registration(status, claimed_at, shipment_id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (8301, 'logistics:express:config:read', '查看电子面单配置'),
    (8302, 'logistics:express:config:write', '修改电子面单配置'),
    (8303, 'order:waybill:manage', '管理订单电子面单'),
    (8304, 'order:waybill:print', '打印订单电子面单'),
    (8305, 'order:waybill:test', '测试电子面单沙箱'),
    (8306, 'order:shipping:registration:retry', '重试物流轨迹注册');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    502, 830, 'OrderLogisticsConfig', 'logistics-config', '/order/logistics-config',
    '电子面单配置', 'ri:printer-line', 53, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, 502
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (8301, 8302, 8303, 8304, 8305, 8306);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (502, 8301),
    (502, 8302),
    (501, 8303),
    (501, 8304),
    (501, 8305),
    (501, 8306);
