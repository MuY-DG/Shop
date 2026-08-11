CREATE TABLE wechat_service_card_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    capture_enabled BOOLEAN NOT NULL,
    worker_enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wechat_service_card_runtime_singleton CHECK (id = 1),
    CONSTRAINT chk_wechat_service_card_runtime_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_service_card_runtime_worker CHECK (
        worker_enabled = FALSE OR capture_enabled = TRUE
    )
);

CREATE TABLE wechat_service_card_runtime_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision BIGINT NOT NULL,
    capture_enabled_before BOOLEAN NOT NULL,
    worker_enabled_before BOOLEAN NOT NULL,
    capture_enabled_after BOOLEAN NOT NULL,
    worker_enabled_after BOOLEAN NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    operator_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wechat_service_card_runtime_audit_revision UNIQUE (revision),
    CONSTRAINT chk_wechat_service_card_runtime_audit_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_service_card_runtime_audit_before CHECK (
        worker_enabled_before = FALSE OR capture_enabled_before = TRUE
    ),
    CONSTRAINT chk_wechat_service_card_runtime_audit_after CHECK (
        worker_enabled_after = FALSE OR capture_enabled_after = TRUE
    )
);

CREATE INDEX idx_wechat_service_card_runtime_audit_time
    ON wechat_service_card_runtime_audit(created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (22001, 'wechat-service-card:read', '查看微信服务动态运行状态'),
    (22002, 'wechat-service-card:runtime:write', '修改微信服务动态运行开关');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT DISTINCT existing.role_id, 22001
FROM admin_role_permission existing
JOIN admin_permission source_permission
  ON source_permission.id = existing.permission_id
 AND source_permission.auth_mark = 'order:read'
WHERE 1 = 1
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission granted
      WHERE granted.role_id = existing.role_id
        AND granted.permission_id = 22001
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 22002
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 22001
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = 22001
  );

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    806, 800, 'WechatServiceCard', 'wechat-service-card',
    '/configuration/wechat-service-card', '微信服务动态', 'ri:wechat-2-line',
    86, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT role_permission.role_id, 800
FROM admin_role_permission role_permission
WHERE role_permission.permission_id IN (22001, 22002)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_permission.role_id
        AND existing.menu_id = 800
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT role_permission.role_id, 806
FROM admin_role_permission role_permission
WHERE role_permission.permission_id IN (22001, 22002);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (806, 22001),
    (806, 22002);
