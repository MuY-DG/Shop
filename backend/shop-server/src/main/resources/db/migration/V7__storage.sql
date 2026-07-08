CREATE TABLE storage_asset_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_storage_asset_category_code UNIQUE (code)
);

CREATE TABLE storage_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    purpose VARCHAR(40) NOT NULL,
    asset_category_id BIGINT NULL,
    visibility VARCHAR(20) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    bucket VARCHAR(64) NOT NULL DEFAULT '',
    object_key VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    extension VARCHAR(20) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL DEFAULT '',
    width INT NULL,
    height INT NULL,
    alt_text VARCHAR(255) NOT NULL DEFAULT '',
    tags_json TEXT NULL,
    public_url VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    uploaded_by_type VARCHAR(20) NOT NULL,
    uploaded_by_id BIGINT NOT NULL,
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_storage_file_object_key UNIQUE (object_key)
);

CREATE TABLE storage_file_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    usage_type VARCHAR(40) NOT NULL,
    owner_type VARCHAR(40) NOT NULL,
    owner_id BIGINT NOT NULL,
    owner_label VARCHAR(255) NOT NULL DEFAULT '',
    snapshot_url VARCHAR(500) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    protected BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE home_banner (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(128) NOT NULL,
    subtitle VARCHAR(255) NOT NULL DEFAULT '',
    image_file_id BIGINT NULL,
    image_url VARCHAR(500) NOT NULL DEFAULT '',
    jump_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    jump_target_id BIGINT NULL,
    jump_path VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    sort_order INT NOT NULL DEFAULT 0,
    start_at TIMESTAMP NULL,
    end_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE product_category ADD COLUMN icon_file_id BIGINT NULL;
ALTER TABLE product_spu ADD COLUMN main_image_file_id BIGINT NULL;
ALTER TABLE product_spu_image ADD COLUMN file_id BIGINT NULL;
ALTER TABLE product_sku ADD COLUMN image_file_id BIGINT NULL;
ALTER TABLE order_item ADD COLUMN main_image_file_id BIGINT NULL;
ALTER TABLE order_item ADD COLUMN sku_image_file_id BIGINT NULL;
ALTER TABLE order_item ADD COLUMN display_image_file_id BIGINT NULL;

CREATE INDEX idx_storage_asset_category_parent_sort ON storage_asset_category(parent_id, sort_order);
CREATE INDEX idx_storage_file_purpose_status_created ON storage_file(purpose, status, created_at);
CREATE INDEX idx_storage_file_category_status_created ON storage_file(asset_category_id, status, created_at);
CREATE INDEX idx_storage_file_usage_file_status ON storage_file_usage(file_id, status);
CREATE INDEX idx_home_banner_status_sort ON home_banner(status, sort_order);

INSERT INTO storage_asset_category (id, parent_id, name, code, description, sort_order, status)
VALUES
    (1, 0, '商品图片', 'PRODUCT_IMAGE', '商品主图与图库素材', 10, 'ENABLED'),
    (2, 0, '首页轮播', 'HOME_BANNER', '首页轮播图素材', 20, 'ENABLED'),
    (3, 0, '分类图标', 'CATEGORY_ICON', '商品分类图标素材', 30, 'ENABLED'),
    (4, 0, '小程序图标', 'APP_ICON', '小程序业务图标素材', 40, 'ENABLED'),
    (5, 0, '富文本图片', 'RICH_TEXT_IMAGE', '富文本编辑图片素材', 50, 'ENABLED'),
    (6, 0, '运营活动', 'MARKETING_IMAGE', '运营活动图片素材', 60, 'ENABLED'),
    (7, 0, '售后凭证', 'AFTER_SALE_IMAGE', '售后与退款凭证素材', 70, 'ENABLED'),
    (8, 0, '支付证书', 'PAYMENT_CERTIFICATE', '支付证书与密钥素材', 80, 'ENABLED'),
    (9, 0, '通用素材', 'GENERIC', '通用素材分类', 90, 'ENABLED');

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (7001, 'file:upload', 'Upload file'),
    (7002, 'file:read', 'Read file'),
    (7003, 'file:delete', 'Delete file'),
    (7004, 'file:category', 'Manage file category'),
    (7101, 'content:banner:read', 'Read home banner'),
    (7102, 'content:banner:create', 'Create home banner'),
    (7103, 'content:banner:update', 'Update home banner'),
    (7104, 'content:banner:publish', 'Publish home banner');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (600, NULL, 'StorageFiles', '/storage/files', '/storage/files', '文件管理', 'ri:folder-upload-line', 60, TRUE, TRUE, TRUE),
    (610, NULL, 'HomeBanner', '/content/banner', '/content/banner', '首页轮播', 'ri:image-2-line', 61, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 600), (1, 610);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 7001), (1, 7002), (1, 7003), (1, 7004),
    (1, 7101), (1, 7102), (1, 7103), (1, 7104);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (600, 7001), (600, 7002), (600, 7003), (600, 7004),
    (610, 7101), (610, 7102), (610, 7103), (610, 7104);
