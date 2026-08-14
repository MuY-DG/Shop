CREATE TABLE wechat_shipping_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    upload_enabled BOOLEAN NOT NULL,
    delivery_enabled BOOLEAN NOT NULL,
    receipt_reconciliation_enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wechat_shipping_runtime_singleton CHECK (id = 1),
    CONSTRAINT chk_wechat_shipping_runtime_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_shipping_runtime_delivery CHECK (
        delivery_enabled = FALSE OR upload_enabled = TRUE
    ),
    CONSTRAINT chk_wechat_shipping_runtime_receipt CHECK (
        receipt_reconciliation_enabled = FALSE OR upload_enabled = TRUE
    )
);

CREATE TABLE wechat_shipping_runtime_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision BIGINT NOT NULL,
    upload_enabled_before BOOLEAN NOT NULL,
    delivery_enabled_before BOOLEAN NOT NULL,
    receipt_reconciliation_enabled_before BOOLEAN NOT NULL,
    upload_enabled_after BOOLEAN NOT NULL,
    delivery_enabled_after BOOLEAN NOT NULL,
    receipt_reconciliation_enabled_after BOOLEAN NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    operator_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wechat_shipping_runtime_audit_revision UNIQUE (revision),
    CONSTRAINT chk_wechat_shipping_runtime_audit_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_shipping_runtime_audit_before_delivery CHECK (
        delivery_enabled_before = FALSE OR upload_enabled_before = TRUE
    ),
    CONSTRAINT chk_wechat_shipping_runtime_audit_before_receipt CHECK (
        receipt_reconciliation_enabled_before = FALSE OR upload_enabled_before = TRUE
    ),
    CONSTRAINT chk_wechat_shipping_runtime_audit_after_delivery CHECK (
        delivery_enabled_after = FALSE OR upload_enabled_after = TRUE
    ),
    CONSTRAINT chk_wechat_shipping_runtime_audit_after_receipt CHECK (
        receipt_reconciliation_enabled_after = FALSE OR upload_enabled_after = TRUE
    )
);

CREATE INDEX idx_wechat_shipping_runtime_audit_time
    ON wechat_shipping_runtime_audit(created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (22003, 'wechat-shipping:runtime:write', '修改微信发货运行开关'),
    (22004, 'wechat-shipping:runtime:read', '查看微信发货运行开关');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 22003
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 22004
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = 22004
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (502, 22003),
    (502, 22004);
