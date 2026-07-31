ALTER TABLE customer_service_agent_profile
    ADD COLUMN auto_accept_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE customer_service_agent_profile
    ADD COLUMN auto_accept_below INT NOT NULL DEFAULT 5;

ALTER TABLE customer_service_agent_profile
    ADD COLUMN auto_accept_count INT NOT NULL DEFAULT 1;

ALTER TABLE customer_service_agent_profile
    ADD COLUMN bound_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE customer_service_agent_profile
    ADD CONSTRAINT chk_customer_service_auto_accept_below CHECK (
        auto_accept_below >= 1 AND auto_accept_below <= 1000
    );

ALTER TABLE customer_service_agent_profile
    ADD CONSTRAINT chk_customer_service_auto_accept_count CHECK (
        auto_accept_count >= 1 AND auto_accept_count <= 1000
    );

ALTER TABLE customer_service_agent_state
    MODIFY COLUMN max_active_conversations INT NULL DEFAULT NULL;

UPDATE customer_service_agent_state
SET max_active_conversations = NULL;

ALTER TABLE customer_service_agent_state
    ADD CONSTRAINT chk_customer_service_max_active_conversations CHECK (
        max_active_conversations IS NULL
        OR (max_active_conversations >= 1 AND max_active_conversations <= 1000)
    );

ALTER TABLE customer_service_config
    ADD COLUMN avatar_file_id BIGINT NULL;

ALTER TABLE customer_service_config
    MODIFY COLUMN avatar VARCHAR(500) NOT NULL DEFAULT '';

UPDATE customer_service_config
SET auto_assign_enabled = TRUE
WHERE id = 1;

CREATE INDEX idx_customer_service_conversation_agent_active
    ON customer_service_conversation(assigned_admin_user_id, status, id);

INSERT INTO admin_role (code, name, description, enabled)
SELECT 'R_GUEST', '游客', '仅可查看系统介绍', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM admin_role WHERE code = 'R_GUEST'
);

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled, full_page
)
VALUES (
    860, NULL, 'GuestIntroduction', '/guest', '/guest/index',
    '系统介绍', 'ri:information-line', 100, TRUE, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, 860
FROM admin_role role_item
WHERE role_item.code = 'R_GUEST'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_item.id
        AND existing.menu_id = 860
  );

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (16013, 'customer-service:settings:update', '修改个人客服设置');

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 842, permission_item.id
FROM admin_permission permission_item
WHERE permission_item.id = 16013
  AND NOT EXISTS (
      SELECT 1
      FROM admin_menu_permission existing
      WHERE existing.menu_id = 842
        AND existing.permission_id = permission_item.id
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, 842
FROM admin_role role_item
WHERE role_item.code = 'R_CUSTOMER_SERVICE'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_item.id
        AND existing.menu_id = 842
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE')
  AND permission_item.id = 16013
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = permission_item.id
  );

DELETE FROM admin_role_permission
WHERE permission_id = 16011
  AND role_id IN (
      SELECT role_item.id
      FROM admin_role role_item
      WHERE role_item.code <> 'R_CUSTOMER_SERVICE_MANAGER'
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
  AND permission_item.auth_mark IN ('asset:upload', 'asset:read')
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = permission_item.id
  );
