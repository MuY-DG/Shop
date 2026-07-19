ALTER TABLE home_product_item
    ADD COLUMN badge_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';

ALTER TABLE home_product_item
    ADD COLUMN custom_badge_text VARCHAR(24) NOT NULL DEFAULT '';

ALTER TABLE home_product_item
    ADD CONSTRAINT chk_home_product_item_badge_mode
        CHECK (badge_mode IN ('AUTO', 'CUSTOM', 'HIDDEN'));

CREATE TABLE home_product_fill_guard (
    section_type VARCHAR(16) PRIMARY KEY,
    CONSTRAINT chk_home_product_fill_guard_section
        CHECK (section_type IN ('HOT', 'RECOMMENDED'))
);

INSERT INTO home_product_fill_guard (section_type) VALUES ('HOT');
INSERT INTO home_product_fill_guard (section_type) VALUES ('RECOMMENDED');

CREATE TABLE product_parameter_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parameter_code VARCHAR(64) NOT NULL,
    parameter_name VARCHAR(64) NOT NULL,
    value_type VARCHAR(24) NOT NULL,
    unit VARCHAR(24) NOT NULL DEFAULT '',
    description VARCHAR(255) NOT NULL DEFAULT '',
    required_value BOOLEAN NOT NULL DEFAULT FALSE,
    filterable BOOLEAN NOT NULL DEFAULT FALSE,
    card_visible BOOLEAN NOT NULL DEFAULT FALSE,
    detail_visible BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_parameter_definition_code UNIQUE (parameter_code),
    CONSTRAINT chk_product_parameter_definition_type
        CHECK (value_type IN ('TEXT', 'NUMBER', 'SINGLE_SELECT', 'MULTI_SELECT', 'BOOLEAN')),
    CONSTRAINT chk_product_parameter_definition_status
        CHECK (status IN ('ENABLED', 'DISABLED'))
);

CREATE TABLE product_parameter_option (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parameter_id BIGINT NOT NULL,
    option_code VARCHAR(64) NOT NULL,
    option_label VARCHAR(64) NOT NULL,
    display_level INT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_parameter_option_code UNIQUE (parameter_id, option_code)
);

CREATE TABLE product_category_parameter (
    category_id BIGINT NOT NULL,
    parameter_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (category_id, parameter_id)
);

CREATE TABLE product_spu_parameter_value (
    spu_id BIGINT NOT NULL,
    parameter_id BIGINT NOT NULL,
    text_value VARCHAR(500) NULL,
    number_value DECIMAL(20, 6) NULL,
    boolean_value BOOLEAN NULL,
    option_codes_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (spu_id, parameter_id)
);

CREATE INDEX idx_product_parameter_definition_status_sort
    ON product_parameter_definition(status, sort_order, id);
CREATE INDEX idx_product_parameter_option_parameter_sort
    ON product_parameter_option(parameter_id, sort_order, id);
CREATE INDEX idx_product_category_parameter_parameter
    ON product_category_parameter(parameter_id, category_id);
CREATE INDEX idx_product_spu_parameter_value_parameter
    ON product_spu_parameter_value(parameter_id, spu_id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (2701, 'product:parameter:read', 'Read product parameters'),
    (2702, 'product:parameter:write', 'Manage product parameters');

INSERT INTO admin_menu
    (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (305, 300, 'ProductParameter', 'parameter', '/product/parameter',
     '商品参数', 'ri:input-field', 35, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES (1, 305);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES (1, 2701), (1, 2702);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (305, 2701), (305, 2702);
