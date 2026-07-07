CREATE TABLE product_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    icon VARCHAR(255) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_category_parent_name UNIQUE (parent_id, name)
);

CREATE TABLE product_spu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    subtitle VARCHAR(255) NOT NULL DEFAULT '',
    main_image VARCHAR(500) NOT NULL DEFAULT '',
    selling_points TEXT NOT NULL,
    detail_html TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_spu_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spu_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_sku (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spu_id BIGINT NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    spec_json TEXT NOT NULL,
    spec_text VARCHAR(255) NOT NULL,
    price_cent BIGINT NOT NULL,
    original_price_cent BIGINT NOT NULL DEFAULT 0,
    stock_available INT NOT NULL DEFAULT 0,
    weight_gram INT NOT NULL DEFAULT 0,
    image VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_sku_code UNIQUE (sku_code),
    CONSTRAINT uk_product_sku_spu_spec UNIQUE (spu_id, spec_text)
);

CREATE TABLE stock_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    quantity_before INT NOT NULL,
    quantity_delta INT NOT NULL,
    quantity_after INT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator_type VARCHAR(20) NOT NULL,
    operator_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_product_category_parent_sort ON product_category(parent_id, sort_order);
CREATE INDEX idx_product_spu_category_status_sort ON product_spu(category_id, status, sort_order);
CREATE INDEX idx_product_spu_status_sort ON product_spu(status, sort_order);
CREATE INDEX idx_product_spu_image_spu_sort ON product_spu_image(spu_id, sort_order);
CREATE INDEX idx_product_sku_spu_status_sort ON product_sku(spu_id, status, sort_order);
CREATE INDEX idx_stock_log_sku_created ON stock_log(sku_id, created_at);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (2001, 'product:category:create', 'Create product category'),
    (2002, 'product:category:update', 'Update product category'),
    (2101, 'product:spu:create', 'Create product SPU'),
    (2102, 'product:spu:update', 'Update product SPU'),
    (2103, 'product:spu:publish', 'Publish product SPU'),
    (2201, 'product:sku:stock', 'Adjust SKU stock');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (300, NULL, 'Product', '/product', '/index/index', '商品管理', 'ri:shopping-bag-3-line', 30, FALSE, TRUE, TRUE),
    (301, 300, 'ProductCategory', 'category', '/product/category', '商品分类', 'ri:folder-3-line', 31, TRUE, TRUE, TRUE),
    (302, 300, 'ProductSpu', 'spu', '/product/spu', 'SPU商品', 'ri:shopping-bag-line', 32, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 300), (1, 301), (1, 302);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 2001), (1, 2002), (1, 2101), (1, 2102), (1, 2103), (1, 2201);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (301, 2001), (301, 2002),
    (302, 2101), (302, 2102), (302, 2103), (302, 2201);
