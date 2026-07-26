CREATE TABLE image_compression_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    admin_configured BOOLEAN NOT NULL DEFAULT FALSE,
    requested_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_source VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    api_key_ciphertext VARCHAR(1000) NOT NULL DEFAULT '',
    monthly_limit INT NOT NULL DEFAULT 500,
    provider_count INT NOT NULL DEFAULT 0,
    provider_count_known BOOLEAN NOT NULL DEFAULT FALSE,
    usage_key_fingerprint VARCHAR(64) NOT NULL DEFAULT '',
    quota_period VARCHAR(7) NOT NULL DEFAULT '',
    last_checked_at TIMESTAMP NULL,
    auto_disabled_reason VARCHAR(64) NOT NULL DEFAULT '',
    secret_cipher_version SMALLINT NOT NULL DEFAULT 1,
    secret_key_id VARCHAR(64) NOT NULL DEFAULT '',
    secret_revision BIGINT NOT NULL DEFAULT 0,
    secret_reencrypted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_image_compression_singleton CHECK (id = 1),
    CONSTRAINT chk_image_compression_source CHECK (config_source IN ('AUTO', 'ENV', 'DB')),
    CONSTRAINT chk_image_compression_monthly_limit CHECK (monthly_limit > 0),
    CONSTRAINT chk_image_compression_provider_count CHECK (provider_count >= 0),
    CONSTRAINT chk_image_compression_disabled_reason CHECK (
        auto_disabled_reason IN ('', 'QUOTA_EXHAUSTED', 'INVALID_KEY')
    )
);

CREATE TABLE image_compression_reservation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    setting_id BIGINT NOT NULL DEFAULT 1,
    usage_key_fingerprint VARCHAR(64) NOT NULL,
    quota_period VARCHAR(7) NOT NULL,
    reserved_count INT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_image_compression_reservation_setting CHECK (setting_id = 1),
    CONSTRAINT chk_image_compression_reserved_count CHECK (reserved_count > 0),
    CONSTRAINT fk_image_compression_reservation_setting
        FOREIGN KEY (setting_id) REFERENCES image_compression_runtime_setting(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_image_compression_reservation_expiry
    ON image_compression_reservation (expires_at);

INSERT INTO payment_secret_rotation_checkpoint
    (checkpoint_name, cursor_value, scan_epoch)
VALUES
    ('image-compression-runtime-setting', '0', 0);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (18003, 'image-compression:config:read', 'Read image compression config'),
    (18004, 'image-compression:config:write', 'Manage image compression config');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    804, 800, 'ImageCompressionConfig', 'image-compression',
    '/configuration/image-compression', '图片压缩配置', 'ri:image-2-line',
    84, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_entry.id, permission_entry.id
FROM admin_role role_entry
JOIN admin_permission permission_entry ON permission_entry.id IN (18003, 18004)
WHERE role_entry.code = 'R_SUPER';

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_entry.id, 804
FROM admin_role role_entry
WHERE role_entry.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (804, 18003),
    (804, 18004);
