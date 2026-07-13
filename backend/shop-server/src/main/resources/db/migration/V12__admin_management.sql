ALTER TABLE admin_user MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE admin_role MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (1000, 'system:user:read', 'Read admin users'),
    (1100, 'system:role:read', 'Read roles'),
    (1104, 'system:role:delete', 'Delete role');

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 1000), (1, 1100), (1, 1104);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (201, 1000),
    (202, 1100), (202, 1104);
