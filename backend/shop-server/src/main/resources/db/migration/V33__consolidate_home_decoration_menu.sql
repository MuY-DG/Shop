INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT source_grant.role_id, 610
FROM admin_role_menu source_grant
WHERE source_grant.menu_id IN (621, 622, 623)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = source_grant.role_id
        AND existing.menu_id = 610
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT source_grant.role_id, 620
FROM admin_role_menu source_grant
WHERE source_grant.menu_id IN (610, 621, 622, 623)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = source_grant.role_id
        AND existing.menu_id = 620
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 610, permission.id
FROM admin_permission permission
WHERE permission.auth_mark IN (
    'content:home-category:read',
    'content:home-category:write',
    'content:home-hot:read',
    'content:home-hot:write',
    'content:home-recommended:read',
    'content:home-recommended:write'
)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_menu_permission existing
      WHERE existing.menu_id = 610
        AND existing.permission_id = permission.id
  );

DELETE FROM admin_role_menu
WHERE menu_id IN (621, 622, 623);

UPDATE admin_menu
SET name = 'HomeDecoration',
    path = 'home',
    component = '/content/home-decoration',
    title = '首页装修',
    icon = 'ri:smartphone-line',
    sort_order = 61,
    keep_alive = TRUE,
    visible = TRUE,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 610;

UPDATE admin_menu
SET visible = FALSE,
    enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (621, 622, 623);
