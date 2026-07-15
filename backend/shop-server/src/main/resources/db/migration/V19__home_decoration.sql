CREATE TABLE home_category_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    image_file_id BIGINT NOT NULL,
    image_url VARCHAR(2048) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_home_category_item_category UNIQUE (category_id),
    CONSTRAINT chk_home_category_item_status CHECK (status IN ('ENABLED', 'DISABLED'))
);

CREATE INDEX idx_home_category_item_status_sort
    ON home_category_item(status, sort_order, id);

CREATE TABLE home_product_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_type VARCHAR(16) NOT NULL,
    spu_id BIGINT NOT NULL,
    image_file_id BIGINT NULL,
    image_url VARCHAR(2048) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_home_product_item_section_spu UNIQUE (section_type, spu_id),
    CONSTRAINT chk_home_product_item_section CHECK (section_type IN ('HOT', 'RECOMMENDED')),
    CONSTRAINT chk_home_product_item_status CHECK (status IN ('ENABLED', 'DISABLED'))
);

CREATE INDEX idx_home_product_item_section_status_sort
    ON home_product_item(section_type, status, sort_order, id);

CREATE TABLE app_contact_setting (
    id TINYINT PRIMARY KEY,
    phone_number VARCHAR(32) NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_app_contact_setting_singleton CHECK (id = 1)
);

INSERT INTO app_contact_setting (id, phone_number)
VALUES (1, '');

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (7201, 'content:home-category:read', 'Read home categories'),
    (7202, 'content:home-category:write', 'Manage home categories'),
    (7301, 'content:home-hot:read', 'Read home hot products'),
    (7302, 'content:home-hot:write', 'Manage home hot products'),
    (7401, 'content:home-recommended:read', 'Read home recommended products'),
    (7402, 'content:home-recommended:write', 'Manage home recommended products'),
    (7501, 'content:contact:read', 'Read app contact setting'),
    (7502, 'content:contact:write', 'Manage app contact setting');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (620, NULL, 'Decoration', '/decoration', '/index/index', '装修管理', 'ri:layout-masonry-line', 60, FALSE, TRUE, TRUE),
    (621, 620, 'HomeCategory', 'category', '/content/home-category', '首页分类', 'ri:function-line', 62, TRUE, TRUE, TRUE),
    (622, 620, 'HomeHotProduct', 'hot-products', '/content/home-hot', '首页热门商品', 'ri:fire-line', 63, TRUE, TRUE, TRUE),
    (623, 620, 'HomeRecommendedProduct', 'recommended-products', '/content/home-recommend', '首页推荐商品', 'ri:star-smile-line', 64, TRUE, TRUE, TRUE),
    (624, 620, 'ContactSetting', 'contact', '/content/contact', '联系我', 'ri:phone-line', 65, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT existing.role_id, 620
FROM admin_role_menu existing
WHERE existing.menu_id IN (600, 610)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu parent_grant
      WHERE parent_grant.role_id = existing.role_id
        AND parent_grant.menu_id = 620
  );

UPDATE admin_menu
SET parent_id = 620,
    name = 'HomeBanner',
    path = 'banner',
    component = '/content/banner',
    title = '首页轮播图',
    sort_order = 61,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 610;

UPDATE admin_menu
SET parent_id = 620,
    name = 'AssetLibrary',
    path = 'assets',
    component = '/storage/files',
    title = '素材库',
    sort_order = 66,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 600;

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 621), (1, 622), (1, 623), (1, 624);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 7201), (1, 7202),
    (1, 7301), (1, 7302),
    (1, 7401), (1, 7402),
    (1, 7501), (1, 7502);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (621, 7201), (621, 7202),
    (622, 7301), (622, 7302),
    (623, 7401), (623, 7402),
    (624, 7501), (624, 7502);
