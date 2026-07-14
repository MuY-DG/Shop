CREATE TABLE storage_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    provider VARCHAR(24) NOT NULL,
    public_base_url VARCHAR(500) NOT NULL,
    local_root VARCHAR(500) NOT NULL,
    cos_region VARCHAR(64) NOT NULL DEFAULT '',
    cos_bucket VARCHAR(128) NOT NULL DEFAULT '',
    cos_secret_id_ciphertext VARCHAR(1000) NOT NULL DEFAULT '',
    cos_secret_key_ciphertext VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_storage_runtime_setting_provider CHECK (provider IN ('LOCAL', 'TENCENT_COS'))
);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (15001, 'storage:config:read', 'Read object storage config'),
    (15002, 'storage:config:write', 'Write object storage config');

DELETE FROM admin_menu_permission WHERE menu_id = 801;
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT source_role_menu.role_id, 800
FROM admin_role_menu source_role_menu
WHERE source_role_menu.menu_id = 801
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = source_role_menu.role_id
        AND existing.menu_id = 800
  );
DELETE FROM admin_role_menu WHERE menu_id = 801;
DELETE FROM admin_menu WHERE id = 801;

UPDATE admin_menu
SET name = 'DeveloperConfig',
    path = '/development/config',
    component = '/development/config',
    title = '开发配置',
    icon = 'ri:code-box-line',
    sort_order = 80,
    keep_alive = TRUE,
    visible = TRUE,
    enabled = TRUE
WHERE id = 800;

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 15001), (1, 15002);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (800, 8001), (800, 8002), (800, 8003),
    (800, 15001), (800, 15002);
