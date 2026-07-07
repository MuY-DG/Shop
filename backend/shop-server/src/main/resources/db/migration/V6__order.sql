CREATE TABLE shop_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'CART',
    idempotency_key VARCHAR(80) NOT NULL,
    product_original_amount_cent BIGINT NOT NULL DEFAULT 0,
    product_amount_cent BIGINT NOT NULL DEFAULT 0,
    user_coupon_id BIGINT NULL,
    coupon_name VARCHAR(80) NOT NULL DEFAULT '',
    coupon_discount_cent BIGINT NOT NULL DEFAULT 0,
    freight_cent BIGINT NOT NULL DEFAULT 0,
    payable_amount_cent BIGINT NOT NULL DEFAULT 0,
    paid_amount_cent BIGINT NOT NULL DEFAULT 0,
    receiver_name VARCHAR(64) NOT NULL DEFAULT '',
    receiver_phone VARCHAR(32) NOT NULL DEFAULT '',
    receiver_address VARCHAR(255) NOT NULL DEFAULT '',
    payment_transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    merchant_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    close_reason VARCHAR(64) NOT NULL DEFAULT '',
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    product_title VARCHAR(128) NOT NULL,
    product_subtitle VARCHAR(255) NOT NULL DEFAULT '',
    main_image VARCHAR(500) NOT NULL DEFAULT '',
    sku_image VARCHAR(500) NOT NULL DEFAULT '',
    display_image VARCHAR(500) NOT NULL DEFAULT '',
    sku_code VARCHAR(64) NOT NULL,
    spec_text VARCHAR(255) NOT NULL DEFAULT '',
    original_price_cent BIGINT NOT NULL DEFAULT 0,
    unit_price_cent BIGINT NOT NULL DEFAULT 0,
    quantity INT NOT NULL,
    line_original_amount_cent BIGINT NOT NULL DEFAULT 0,
    line_amount_cent BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stock_lock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    locked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_shop_order_user_idempotency ON shop_order(user_id, idempotency_key);
CREATE UNIQUE INDEX uk_shop_order_order_no ON shop_order(order_no);
CREATE INDEX idx_shop_order_user_status_created ON shop_order(user_id, status, created_at);
CREATE INDEX idx_order_item_order ON order_item(order_id);
CREATE INDEX idx_stock_lock_order ON stock_lock(order_id, status);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (4001, 'order:read', 'Read order'),
    (4002, 'order:close', 'Close order');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (500, NULL, 'Order', '/order', '/index/index', '订单管理', 'ri:file-list-3-line', 50, FALSE, TRUE, TRUE),
    (501, 500, 'OrderList', 'list', '/order/list', '订单列表', 'ri:file-list-line', 51, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 500), (1, 501);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 4001), (1, 4002);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (501, 4001), (501, 4002);
