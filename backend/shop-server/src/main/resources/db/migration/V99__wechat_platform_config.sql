CREATE TABLE wechat_platform_config (
    id BIGINT NOT NULL PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    app_secret_ciphertext VARCHAR(1000) NOT NULL,
    secret_cipher_version INT NOT NULL,
    secret_key_id VARCHAR(64) NOT NULL DEFAULT '',
    secret_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    imported_from_env_at TIMESTAMP NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    secret_reencrypted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wechat_platform_config_singleton CHECK (id = 1),
    CONSTRAINT chk_wechat_platform_config_app_id CHECK (app_id <> ''),
    CONSTRAINT chk_wechat_platform_config_secret CHECK (app_secret_ciphertext <> ''),
    CONSTRAINT chk_wechat_platform_config_cipher_version CHECK (secret_cipher_version IN (1, 2)),
    CONSTRAINT chk_wechat_platform_config_secret_revision CHECK (secret_revision >= 1),
    CONSTRAINT chk_wechat_platform_config_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_platform_config_key_id CHECK (
        (secret_cipher_version = 1 AND secret_key_id = '')
        OR (secret_cipher_version = 2 AND secret_key_id <> '')
    )
);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (23001, 'wechat-platform:config:read', '查看微信平台配置'),
    (23002, 'wechat-platform:config:write', '修改微信平台配置');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (23001, 23002);

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    807, 800, 'WechatPlatformConfig', 'wechat-platform',
    '/configuration/wechat-platform', '微信平台配置', 'ri:wechat-line',
    87, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, menu_item.id
FROM admin_role role_item
CROSS JOIN admin_menu menu_item
WHERE role_item.code = 'R_SUPER'
  AND menu_item.id IN (800, 807)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_item.id
        AND existing.menu_id = menu_item.id
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (807, 23001),
    (807, 23002);
