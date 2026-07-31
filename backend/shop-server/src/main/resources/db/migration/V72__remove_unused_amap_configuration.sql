DELETE FROM admin_menu_permission
WHERE menu_id = 803
   OR permission_id IN (18001, 18002);

DELETE FROM admin_role_menu
WHERE menu_id = 803;

DELETE FROM admin_role_permission
WHERE permission_id IN (18001, 18002);

DELETE FROM admin_menu
WHERE id = 803;

DELETE FROM admin_permission
WHERE id IN (18001, 18002)
   OR auth_mark IN ('amap:config:read', 'amap:config:write');

DROP TABLE IF EXISTS amap_runtime_setting;
