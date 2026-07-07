CREATE TABLE coupon_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    coupon_type VARCHAR(20) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    threshold_cent BIGINT NOT NULL DEFAULT 0,
    discount_cent BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'ALL',
    scope_value VARCHAR(255) NOT NULL DEFAULT '',
    strategy_key VARCHAR(80) NOT NULL DEFAULT 'coupon.amount-off.v1',
    total_stock INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    per_user_limit INT NOT NULL DEFAULT 1,
    valid_start_at TIMESTAMP NOT NULL,
    valid_end_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_name VARCHAR(80) NOT NULL,
    coupon_type VARCHAR(20) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    threshold_cent BIGINT NOT NULL DEFAULT 0,
    discount_cent BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'ALL',
    scope_value VARCHAR(255) NOT NULL DEFAULT '',
    valid_start_at TIMESTAMP NOT NULL,
    valid_end_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_order_id BIGINT NULL,
    locked_at TIMESTAMP NULL,
    used_order_id BIGINT NULL,
    used_at TIMESTAMP NULL,
    released_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE coupon_claim_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_coupon_id BIGINT NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coupon_template_status_sort ON coupon_template(status, sort_order, id);
CREATE INDEX idx_coupon_template_validity ON coupon_template(valid_start_at, valid_end_at);
CREATE INDEX idx_user_coupon_user_status_valid ON user_coupon(user_id, status, valid_end_at);
CREATE INDEX idx_user_coupon_template_user ON user_coupon(template_id, user_id);
CREATE INDEX idx_coupon_claim_template_user ON coupon_claim_record(template_id, user_id, claimed_at);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (3001, 'coupon:template:create', 'Create coupon template'),
    (3002, 'coupon:template:update', 'Update coupon template'),
    (3003, 'coupon:template:enable', 'Enable coupon template'),
    (3004, 'coupon:template:disable', 'Disable coupon template');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (400, NULL, 'Marketing', '/marketing', '/index/index', '营销管理', 'ri:coupon-3-line', 40, FALSE, TRUE, TRUE),
    (401, 400, 'MarketingCoupon', 'coupon', '/marketing/coupon', '优惠券', 'ri:coupon-line', 41, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 400), (1, 401);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 3001), (1, 3002), (1, 3003), (1, 3004);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (401, 3001), (401, 3002), (401, 3003), (401, 3004);
