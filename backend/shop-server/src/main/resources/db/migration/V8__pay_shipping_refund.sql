CREATE TABLE payment_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_name VARCHAR(80) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    mch_id VARCHAR(32) NOT NULL,
    merchant_serial_no VARCHAR(128) NOT NULL,
    api_v3_key_ciphertext TEXT NOT NULL,
    private_key_file_id BIGINT NULL,
    merchant_certificate_file_id BIGINT NULL,
    verify_mode VARCHAR(32) NOT NULL DEFAULT 'PUBLIC_KEY',
    wechat_public_key_id VARCHAR(128) NOT NULL DEFAULT '',
    wechat_public_key_file_id BIGINT NULL,
    notify_url VARCHAR(255) NOT NULL,
    refund_notify_url VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_config_id BIGINT NULL,
    out_trade_no VARCHAR(64) NOT NULL,
    prepay_id VARCHAR(128) NOT NULL DEFAULT '',
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    payer_openid VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    amount_cent BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    request_digest VARCHAR(64) NOT NULL DEFAULT '',
    callback_digest VARCHAR(64) NOT NULL DEFAULT '',
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    expires_at TIMESTAMP NOT NULL,
    paid_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_callback_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    callback_type VARCHAR(20) NOT NULL,
    notify_id VARCHAR(128) NOT NULL DEFAULT '',
    out_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    out_refund_no VARCHAR(64) NOT NULL DEFAULT '',
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL DEFAULT '',
    resource_digest VARCHAR(64) NOT NULL DEFAULT '',
    raw_body_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(64) NOT NULL DEFAULT '',
    error_message VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    express_company VARCHAR(80) NOT NULL,
    tracking_no VARCHAR(80) NOT NULL,
    shipment_note VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    wechat_upload_status VARCHAR(20) NOT NULL,
    wechat_error_code VARCHAR(64) NOT NULL DEFAULT '',
    wechat_error_message VARCHAR(255) NOT NULL DEFAULT '',
    retry_count INT NOT NULL DEFAULT 0,
    shipped_at TIMESTAMP NULL,
    wechat_uploaded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE after_sale_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    after_sale_type VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    requested_amount_cent BIGINT NOT NULL,
    approved_amount_cent BIGINT NULL,
    audit_note VARCHAR(255) NOT NULL DEFAULT '',
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE after_sale_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE refund_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    payment_order_id BIGINT NOT NULL,
    out_refund_no VARCHAR(64) NOT NULL,
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    refund_amount_cent BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    callback_status VARCHAR(32) NOT NULL DEFAULT '',
    callback_digest VARCHAR(64) NOT NULL DEFAULT '',
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    requested_at TIMESTAMP NOT NULL,
    success_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE shop_order ADD COLUMN paid_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN shipped_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN completed_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN refunding_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN refunded_at TIMESTAMP NULL;

CREATE UNIQUE INDEX uk_payment_order_out_trade_no ON payment_order(out_trade_no);
CREATE INDEX idx_payment_order_order_status ON payment_order(order_id, status);
CREATE INDEX idx_payment_order_expires_status ON payment_order(expires_at, status);
CREATE UNIQUE INDEX uk_order_shipment_order ON order_shipment(order_id);
CREATE INDEX idx_order_shipment_wechat_status ON order_shipment(wechat_upload_status, retry_count);
CREATE INDEX idx_after_sale_order_status ON after_sale_request(order_id, status);
CREATE UNIQUE INDEX uk_refund_order_out_refund_no ON refund_order(out_refund_no);
CREATE INDEX idx_refund_order_after_sale ON refund_order(after_sale_id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (8001, 'payment:config:read', 'Read payment config'),
    (8002, 'payment:config:write', 'Write payment config'),
    (8003, 'payment:config:enable', 'Enable payment config'),
    (8101, 'order:ship', 'Ship order'),
    (8102, 'order:shipping:retry', 'Retry WeChat shipping upload'),
    (8201, 'aftersale:read', 'Read after-sale'),
    (8202, 'aftersale:audit', 'Audit after-sale');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (800, NULL, 'Payment', '/payment', '/index/index', '支付管理', 'ri:bank-card-line', 80, FALSE, TRUE, TRUE),
    (801, 800, 'PaymentConfig', 'config', '/payment/config', '支付配置', 'ri:settings-4-line', 81, TRUE, TRUE, TRUE),
    (820, NULL, 'AfterSale', '/aftersale', '/index/index', '售后管理', 'ri:refund-2-line', 82, FALSE, TRUE, TRUE),
    (821, 820, 'AfterSaleList', 'list', '/aftersale/list', '售后列表', 'ri:customer-service-2-line', 83, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 800), (1, 801), (1, 820), (1, 821);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 8001), (1, 8002), (1, 8003),
    (1, 8101), (1, 8102),
    (1, 8201), (1, 8202);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (801, 8001), (801, 8002), (801, 8003),
    (501, 8101), (501, 8102),
    (821, 8201), (821, 8202);
