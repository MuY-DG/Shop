CREATE TABLE freight_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    charge_mode VARCHAR(20) NOT NULL,
    fixed_amount_cent BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    sort_order INT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO freight_template
    (id, name, charge_mode, fixed_amount_cent, status, sort_order)
VALUES
    (1, '全国包邮', 'FREE', 0, 'ENABLED', 0);

ALTER TABLE product_spu
    ADD COLUMN spec_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE product_spu
    ADD COLUMN main_video VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE product_spu
    ADD COLUMN main_video_file_id BIGINT NULL;
ALTER TABLE product_spu
    ADD COLUMN freight_template_id BIGINT NULL;
ALTER TABLE product_spu
    ADD COLUMN virtual_sales BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product_spu
    ADD COLUMN deleted_at TIMESTAMP NULL;

UPDATE product_spu
SET freight_template_id = 1
WHERE freight_template_id IS NULL;

ALTER TABLE product_spu
    MODIFY COLUMN freight_template_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE product_sku
    ADD COLUMN cost_price_cent BIGINT NULL;
ALTER TABLE product_sku
    ADD COLUMN volume_cubic_meter DECIMAL(12, 6) NULL;
ALTER TABLE product_sku
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product_sku
    ADD COLUMN combination_key VARCHAR(512) NULL;
ALTER TABLE product_sku
    ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE product_sku
    MODIFY COLUMN weight_gram INT NULL;

UPDATE product_sku
SET combination_key = CONCAT('legacy-', id)
WHERE combination_key IS NULL OR combination_key = '';

UPDATE product_spu
SET spec_type = 'MULTI'
WHERE id IN (
    SELECT legacy_multi_spu.spu_id
    FROM (
        SELECT spu_id
        FROM product_sku
        GROUP BY spu_id
        HAVING COUNT(*) > 1
    ) legacy_multi_spu
);

UPDATE product_sku
SET is_default = TRUE
WHERE id IN (
    SELECT default_sku_id
    FROM (
        SELECT COALESCE(MIN(CASE WHEN status = 'ENABLED' THEN id END), MIN(id)) AS default_sku_id
        FROM product_sku
        GROUP BY spu_id
    ) legacy_default_skus
);

ALTER TABLE product_sku
    MODIFY COLUMN combination_key VARCHAR(512) NOT NULL DEFAULT '';

CREATE TABLE product_spec_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_spec_template_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    group_key VARCHAR(64) NOT NULL,
    name VARCHAR(30) NOT NULL,
    image_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE product_spec_template_value (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    value_key VARCHAR(64) NOT NULL,
    value_name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE product_spu_spec_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spu_id BIGINT NOT NULL,
    group_key VARCHAR(64) NOT NULL,
    name VARCHAR(30) NOT NULL,
    image_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_spu_spec_value (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    value_key VARCHAR(64) NOT NULL,
    value_name VARCHAR(64) NOT NULL,
    image VARCHAR(500) NOT NULL DEFAULT '',
    image_file_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_sku_spec_value (
    sku_id BIGINT NOT NULL,
    spec_value_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (sku_id, spec_value_id)
);

CREATE TABLE product_guarantee_service (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    terms_name VARCHAR(64) NOT NULL,
    content_description VARCHAR(500) NOT NULL DEFAULT '',
    icon VARCHAR(500) NOT NULL DEFAULT '',
    icon_file_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_spu_guarantee_service (
    spu_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (spu_id, service_id)
);

CREATE TABLE product_spu_tag (
    spu_id BIGINT NOT NULL,
    tag_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (spu_id, tag_code)
);

CREATE TABLE product_spu_coupon (
    spu_id BIGINT NOT NULL,
    coupon_template_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (spu_id, coupon_template_id)
);

CREATE INDEX idx_freight_template_active_sort
    ON freight_template(deleted_at, status, sort_order, id);
CREATE INDEX idx_product_spu_active_status_sort
    ON product_spu(deleted_at, status, sort_order, id);
CREATE INDEX idx_product_spu_freight_template
    ON product_spu(freight_template_id);
ALTER TABLE product_sku
    DROP CONSTRAINT uk_product_sku_spu_spec;
CREATE UNIQUE INDEX uk_product_sku_spu_combination
    ON product_sku(spu_id, combination_key);
CREATE INDEX idx_product_sku_spu_deleted_status_sort
    ON product_sku(spu_id, deleted_at, status, sort_order, id);
CREATE INDEX idx_storage_file_usage_owner_status
    ON storage_file_usage(owner_type, owner_id, status, id);
CREATE INDEX idx_order_item_spu
    ON order_item(spu_id);

CREATE UNIQUE INDEX uk_product_spec_template_name
    ON product_spec_template(name);
CREATE UNIQUE INDEX uk_product_spec_template_group_key
    ON product_spec_template_group(template_id, group_key);
CREATE INDEX idx_product_spec_template_group_template_sort
    ON product_spec_template_group(template_id, sort_order, id);
CREATE UNIQUE INDEX uk_product_spec_template_value_key
    ON product_spec_template_value(group_id, value_key);
CREATE INDEX idx_product_spec_template_value_group_sort
    ON product_spec_template_value(group_id, sort_order, id);

CREATE UNIQUE INDEX uk_product_spu_spec_group_key
    ON product_spu_spec_group(spu_id, group_key);
CREATE INDEX idx_product_spu_spec_group_spu_deleted_sort
    ON product_spu_spec_group(spu_id, deleted_at, sort_order, id);
CREATE UNIQUE INDEX uk_product_spu_spec_value_key
    ON product_spu_spec_value(group_id, value_key);
CREATE INDEX idx_product_spu_spec_value_group_deleted_sort
    ON product_spu_spec_value(group_id, deleted_at, sort_order, id);
CREATE INDEX idx_product_sku_spec_value_value
    ON product_sku_spec_value(spec_value_id, sku_id);

CREATE INDEX idx_product_guarantee_active_sort
    ON product_guarantee_service(deleted_at, visible, sort_order, id);
CREATE INDEX idx_product_spu_guarantee_service_service
    ON product_spu_guarantee_service(service_id, spu_id);
CREATE INDEX idx_product_spu_tag_code_spu
    ON product_spu_tag(tag_code, spu_id);
CREATE INDEX idx_product_spu_coupon_template
    ON product_spu_coupon(coupon_template_id, spu_id);

UPDATE admin_menu
SET title = '商品管理', updated_at = CURRENT_TIMESTAMP
WHERE id = 302;

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (2104, 'product:spu:delete', 'Delete product SPU'),
    (2301, 'product:spec-template:create', 'Create product specification template'),
    (2302, 'product:spec-template:update', 'Update product specification template'),
    (2401, 'product:guarantee:create', 'Create product guarantee service'),
    (2402, 'product:guarantee:update', 'Update product guarantee service'),
    (2403, 'product:guarantee:delete', 'Delete product guarantee service'),
    (2404, 'product:guarantee:visibility', 'Change product guarantee visibility'),
    (2501, 'product:freight:create', 'Create product freight template'),
    (2502, 'product:freight:update', 'Update product freight template'),
    (2601, 'product:coupon:bind', 'Bind product coupon'),
    (2602, 'product:coupon:create', 'Create product coupon');

INSERT INTO admin_menu
    (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (303, 300, 'ProductSpecTemplate', 'spec-template', '/product/spec-template',
     '商品规格', 'ri:list-settings-line', 33, TRUE, TRUE, TRUE),
    (304, 300, 'ProductGuaranteeService', 'guarantee-service', '/product/guarantee-service',
     '保障服务', 'ri:shield-check-line', 34, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 303),
    (1, 304);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 2104),
    (1, 2301), (1, 2302),
    (1, 2401), (1, 2402), (1, 2403), (1, 2404),
    (1, 2501), (1, 2502),
    (1, 2601), (1, 2602);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (302, 2104),
    (302, 2501), (302, 2502),
    (302, 2601), (302, 2602),
    (303, 2301), (303, 2302),
    (304, 2401), (304, 2402), (304, 2403), (304, 2404);
