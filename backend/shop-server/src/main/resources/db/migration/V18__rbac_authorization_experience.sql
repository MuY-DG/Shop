DELETE FROM admin_role_permission
WHERE permission_id = 1201;

DELETE FROM admin_menu_permission
WHERE permission_id = 1201;

DELETE FROM admin_permission
WHERE id = 1201
  AND auth_mark = 'system:menu:update';

UPDATE admin_permission
SET auth_mark = 'system:menu:read',
    title = 'Read menu and permission resources'
WHERE id = 1202
  AND auth_mark = 'add';
