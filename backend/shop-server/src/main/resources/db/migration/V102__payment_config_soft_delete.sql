ALTER TABLE payment_config
    ADD COLUMN deleted_at TIMESTAMP NULL;

ALTER TABLE payment_config
    ADD COLUMN deleted_by BIGINT NULL;

ALTER TABLE payment_config
    ADD CONSTRAINT chk_payment_config_delete_state CHECK (
        (status = 'ACTIVE' AND deleted_at IS NULL AND deleted_by IS NULL)
        OR
        (status = 'DELETED' AND enabled = FALSE
            AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND deleted_by > 0)
    );

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (8004, 'payment:config:delete', '删除支付配置');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 8004
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (802, 8004);
