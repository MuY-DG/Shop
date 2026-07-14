UPDATE admin_menu
SET name = 'DeveloperConfig',
    path = '/development',
    component = '/index/index',
    title = '开发配置',
    icon = 'ri:code-box-line',
    sort_order = 80,
    keep_alive = FALSE,
    visible = TRUE,
    enabled = TRUE
WHERE id = 800;

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (801, 800, 'StorageConfig', 'storage', '/development/storage', '对象存储配置', 'ri:cloud-line', 81, TRUE, TRUE, TRUE),
    (802, 800, 'PaymentConfig', 'payment', '/payment/config', '支付配置', 'ri:settings-4-line', 82, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT parent_menu.role_id, 801
FROM admin_role_menu parent_menu
JOIN admin_role_permission role_permission
  ON role_permission.role_id = parent_menu.role_id
 AND role_permission.permission_id IN (15001, 15002)
WHERE parent_menu.menu_id = 800
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = parent_menu.role_id
        AND existing.menu_id = 801
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT parent_menu.role_id, 802
FROM admin_role_menu parent_menu
JOIN admin_role_permission role_permission
  ON role_permission.role_id = parent_menu.role_id
 AND role_permission.permission_id IN (8001, 8002, 8003)
WHERE parent_menu.menu_id = 800
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = parent_menu.role_id
        AND existing.menu_id = 802
  );

DELETE FROM admin_menu_permission WHERE menu_id = 800;
INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (801, 15001), (801, 15002),
    (802, 8001), (802, 8002), (802, 8003);
