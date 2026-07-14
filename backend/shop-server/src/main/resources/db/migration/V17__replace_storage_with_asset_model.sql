CREATE TABLE IF NOT EXISTS storage_asset_folder_guard (
    id TINYINT PRIMARY KEY,
    CONSTRAINT chk_storage_asset_folder_guard_singleton CHECK (id = 1)
);

INSERT INTO storage_asset_folder_guard (id)
SELECT 1
WHERE NOT EXISTS (SELECT 1 FROM storage_asset_folder_guard WHERE id = 1);

CREATE TABLE IF NOT EXISTS storage_asset_folder (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT NULL,
    parent_key BIGINT GENERATED ALWAYS AS (COALESCE(parent_id, 0)),
    name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_storage_asset_folder_parent_name UNIQUE (parent_key, name),
    CONSTRAINT fk_storage_asset_folder_parent FOREIGN KEY (parent_id)
        REFERENCES storage_asset_folder (id) ON DELETE RESTRICT,
    INDEX idx_storage_asset_folder_parent_status_sort (parent_id, status, sort_order, id)
);

CREATE TABLE IF NOT EXISTS storage_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scope VARCHAR(20) NOT NULL,
    media_kind VARCHAR(20) NOT NULL,
    folder_id BIGINT NULL,
    visibility VARCHAR(20) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    storage_container VARCHAR(500) NOT NULL DEFAULT '',
    storage_region VARCHAR(64) NOT NULL DEFAULT '',
    object_key VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    extension VARCHAR(20) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL DEFAULT '',
    width INT NULL,
    height INT NULL,
    duration_seconds INT NULL,
    alt_text VARCHAR(255) NOT NULL DEFAULT '',
    tags_json TEXT NULL,
    public_url VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    uploaded_by_type VARCHAR(20) NOT NULL,
    uploaded_by_id BIGINT NOT NULL,
    upload_context_type VARCHAR(40) NULL,
    upload_context_id BIGINT NULL,
    expires_at TIMESTAMP NULL,
    cleanup_attempts INT NOT NULL DEFAULT 0,
    cleanup_next_retry_at TIMESTAMP NULL,
    cleanup_lease_token VARCHAR(36) NULL,
    deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_storage_asset_object_key UNIQUE (object_key),
    CONSTRAINT chk_storage_asset_scope CHECK (scope IN ('LIBRARY', 'ATTACHMENT', 'SECRET')),
    CONSTRAINT chk_storage_asset_media_kind CHECK (media_kind IN ('IMAGE', 'VIDEO', 'DOCUMENT')),
    CONSTRAINT chk_storage_asset_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT chk_storage_asset_provider CHECK (provider IN ('LOCAL', 'TENCENT_COS')),
    CONSTRAINT chk_storage_asset_scope_visibility CHECK (
        (scope = 'LIBRARY' AND visibility = 'PUBLIC')
        OR (scope IN ('ATTACHMENT', 'SECRET') AND visibility = 'PRIVATE')
    ),
    CONSTRAINT chk_storage_asset_folder_scope CHECK (folder_id IS NULL OR scope = 'LIBRARY'),
    CONSTRAINT chk_storage_asset_upload_context CHECK (
        (upload_context_type IS NULL AND upload_context_id IS NULL)
        OR (upload_context_type IS NOT NULL AND upload_context_id IS NOT NULL)
    ),
    CONSTRAINT fk_storage_asset_folder FOREIGN KEY (folder_id)
        REFERENCES storage_asset_folder (id) ON DELETE RESTRICT,
    INDEX idx_storage_asset_scope_status_created (scope, status, created_at, id),
    INDEX idx_storage_asset_scope_kind_status_created (scope, media_kind, status, created_at, id),
    INDEX idx_storage_asset_folder_status_created (folder_id, status, created_at, id),
    INDEX idx_storage_asset_upload_context (scope, upload_context_type, upload_context_id, status),
    INDEX idx_storage_asset_expiry (scope, status, expires_at, id),
    INDEX idx_storage_asset_cleanup_retry (scope, status, cleanup_next_retry_at, id)
);

CREATE TABLE IF NOT EXISTS storage_asset_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    usage_type VARCHAR(40) NOT NULL,
    owner_type VARCHAR(40) NOT NULL,
    owner_id BIGINT NOT NULL,
    owner_label VARCHAR(255) NOT NULL DEFAULT '',
    snapshot_url VARCHAR(500) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    protected BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_storage_asset_usage_asset FOREIGN KEY (asset_id)
        REFERENCES storage_asset (id) ON DELETE RESTRICT,
    INDEX idx_storage_asset_usage_asset_status (asset_id, status, id),
    INDEX idx_storage_asset_usage_owner_status (owner_type, owner_id, status, id)
);

UPDATE home_banner
SET image_file_id = NULL
WHERE image_file_id IS NOT NULL;

UPDATE order_item
SET main_image_file_id = NULL,
    sku_image_file_id = NULL,
    display_image_file_id = NULL
WHERE main_image_file_id IS NOT NULL
   OR sku_image_file_id IS NOT NULL
   OR display_image_file_id IS NOT NULL;

UPDATE product_category
SET icon_file_id = NULL
WHERE icon_file_id IS NOT NULL;

UPDATE product_spu
SET main_image_file_id = NULL,
    main_video_file_id = NULL
WHERE main_image_file_id IS NOT NULL
   OR main_video_file_id IS NOT NULL;

UPDATE product_spu_image
SET file_id = NULL
WHERE file_id IS NOT NULL;

UPDATE product_sku
SET image_file_id = NULL
WHERE image_file_id IS NOT NULL;

UPDATE product_spu_spec_value
SET image_file_id = NULL
WHERE image_file_id IS NOT NULL;

UPDATE product_guarantee_service
SET icon_file_id = NULL
WHERE icon_file_id IS NOT NULL;

DELETE FROM after_sale_evidence;

UPDATE payment_config
SET private_key_file_id = NULL,
    merchant_certificate_file_id = NULL,
    wechat_public_key_file_id = NULL,
    enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE private_key_file_id IS NOT NULL
   OR merchant_certificate_file_id IS NOT NULL
   OR wechat_public_key_file_id IS NOT NULL
   OR enabled = TRUE;

UPDATE payment_runtime_setting
SET config_source = 'AUTO',
    updated_at = CURRENT_TIMESTAMP
WHERE config_source = 'DB';

UPDATE admin_permission
SET auth_mark = CASE id
        WHEN 7001 THEN 'asset:upload'
        WHEN 7002 THEN 'asset:read'
        WHEN 7003 THEN 'asset:delete'
        WHEN 7004 THEN 'asset:folder'
    END,
    title = CASE id
        WHEN 7001 THEN 'Upload asset'
        WHEN 7002 THEN 'Read asset'
        WHEN 7003 THEN 'Delete asset'
        WHEN 7004 THEN 'Manage asset folder'
    END
WHERE id IN (7001, 7002, 7003, 7004);

UPDATE admin_menu
SET title = '素材库',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 600;

DROP TABLE IF EXISTS storage_file_usage;
DROP TABLE IF EXISTS storage_file;
DROP TABLE IF EXISTS storage_asset_category;
