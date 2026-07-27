ALTER TABLE admin_user
    ADD COLUMN max_sessions INT NOT NULL DEFAULT 0;

ALTER TABLE admin_user
    ADD CONSTRAINT chk_admin_user_max_sessions CHECK (max_sessions >= 0);

ALTER TABLE admin_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

-- Tokens created before session registration do not have subject/session indexes.
-- Bump the version once so those legacy tokens cannot bypass the new session policy.
UPDATE admin_user
SET auth_version = 1;

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (1004, 'system:user:session:read', '查看管理员登录会话'),
    (1005, 'system:user:session:revoke', '撤销管理员登录会话');

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (201, 1004),
    (201, 1005);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
JOIN admin_permission permission_item
  ON permission_item.auth_mark IN (
      'system:user:session:read',
      'system:user:session:revoke'
  )
WHERE role_item.code = 'R_SUPER';
