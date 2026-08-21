INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (3503, 'customer:user:status', '停用或重新启用小程序客户'),
    (19005, 'compliance:cancellation:read', '查看账号注销记录');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (3503, 19005);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (450, 3503);

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    903, 900, 'AccountCancellations', 'cancellations',
    '/compliance/cancellations', '注销记录', 'ri:user-unfollow-line',
    903, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, 903
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (903, 19005);

CREATE TABLE app_user_status_change_audit (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    admin_user_id BIGINT NOT NULL,
    from_status VARCHAR(20) NOT NULL,
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_app_user_status_change_from CHECK (
        from_status IN ('ENABLED', 'DISABLED')
    ),
    CONSTRAINT chk_app_user_status_change_to CHECK (
        to_status IN ('ENABLED', 'DISABLED')
    ),
    CONSTRAINT chk_app_user_status_change_transition CHECK (
        from_status <> to_status
    ),
    CONSTRAINT fk_app_user_status_change_user FOREIGN KEY (user_id)
        REFERENCES app_user(id),
    CONSTRAINT fk_app_user_status_change_admin FOREIGN KEY (admin_user_id)
        REFERENCES admin_user(id)
);

CREATE INDEX idx_app_user_status_change_user_time
    ON app_user_status_change_audit(user_id, created_at, id);
