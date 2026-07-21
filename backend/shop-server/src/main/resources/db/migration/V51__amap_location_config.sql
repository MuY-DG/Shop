CREATE TABLE amap_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    web_service_key_ciphertext VARCHAR(1000) NOT NULL DEFAULT '',
    secret_cipher_version SMALLINT NOT NULL DEFAULT 1,
    secret_key_id VARCHAR(64) NOT NULL DEFAULT '',
    secret_revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (18001, 'amap:config:read', 'Read AMap location config'),
    (18002, 'amap:config:write', 'Manage AMap location config');

UPDATE admin_menu
SET name = 'ConfigManagement',
    title = '配置管理',
    icon = 'ri:settings-4-line'
WHERE id = 800;

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (803, 800, 'AmapConfig', 'amap', '/configuration/amap', '高德地图配置', 'ri:map-pin-2-line', 83, TRUE, TRUE, TRUE);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_entry.id, permission_entry.id
FROM admin_role role_entry
JOIN admin_permission permission_entry ON permission_entry.id IN (18001, 18002)
WHERE role_entry.code = 'R_SUPER';

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_entry.id, 803
FROM admin_role role_entry
WHERE role_entry.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (803, 18001),
    (803, 18002);
