ALTER TABLE admin_menu
    ADD COLUMN full_page BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE admin_menu
SET full_page = TRUE,
    keep_alive = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 840;

CREATE TABLE customer_service_config (
    id BIGINT PRIMARY KEY,
    default_service_name VARCHAR(64) NOT NULL,
    avatar VARCHAR(255) NOT NULL DEFAULT '',
    auto_assign_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    assignment_strategy VARCHAR(32) NOT NULL DEFAULT 'LEAST_LOADED',
    sticky_agent_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sticky_window_hours INT NOT NULL DEFAULT 48,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_config_singleton CHECK (id = 1),
    CONSTRAINT chk_customer_service_sticky_window CHECK (
        sticky_window_hours >= 1 AND sticky_window_hours <= 720
    )
);

INSERT INTO customer_service_config (
    id, default_service_name, avatar, auto_assign_enabled,
    assignment_strategy, sticky_agent_enabled, sticky_window_hours
)
VALUES (1, '商城客服', '', FALSE, 'LEAST_LOADED', TRUE, 48);

CREATE TABLE customer_service_agent_profile (
    admin_user_id BIGINT PRIMARY KEY,
    service_name_override VARCHAR(64) NULL,
    routing_weight INT NOT NULL DEFAULT 100,
    last_assigned_at TIMESTAMP NULL,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_routing_weight CHECK (
        routing_weight >= 1 AND routing_weight <= 1000
    )
);

INSERT INTO customer_service_agent_profile (admin_user_id)
SELECT DISTINCT user_role.user_id
FROM admin_user_role user_role
JOIN admin_role role_item ON role_item.id = user_role.role_id
WHERE role_item.code = 'R_CUSTOMER_SERVICE';

INSERT INTO customer_service_agent_state (
    admin_user_id, work_status, max_active_conversations, updated_at
)
SELECT DISTINCT user_role.user_id, 'BUSY', 5, CURRENT_TIMESTAMP
FROM admin_user_role user_role
JOIN admin_role role_item ON role_item.id = user_role.role_id
WHERE role_item.code = 'R_CUSTOMER_SERVICE'
  AND NOT EXISTS (
      SELECT 1
      FROM customer_service_agent_state agent_state
      WHERE agent_state.admin_user_id = user_role.user_id
  );

INSERT INTO admin_role (code, name, description, enabled)
SELECT 'R_CUSTOMER_SERVICE_MANAGER', '客服管理员', '管理客服成员、对外形象和会话分流', TRUE
WHERE NOT EXISTS (
    SELECT 1
    FROM admin_role
    WHERE code = 'R_CUSTOMER_SERVICE_MANAGER'
);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (16009, 'customer-service:management:read', '查看客服管理'),
    (16010, 'customer-service:routing:update', '修改客服会话分流'),
    (16011, 'customer-service:identity:update', '修改客服对外形象'),
    (16012, 'customer-service:conversation:supervise', '监管并调度客服会话');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled, full_page
)
VALUES
    (850, NULL, 'CustomerServiceManagement', '/customer-service-management', '/index/index',
     '客服管理', 'ri:team-line', 56, FALSE, TRUE, TRUE, FALSE),
    (851, 850, 'CustomerServiceMembers', 'members', '/customer-service-management/members',
     '客服成员', 'ri:user-settings-line', 57, TRUE, TRUE, TRUE, FALSE),
    (852, 850, 'CustomerServiceSettings', 'settings', '/customer-service-management/settings',
     '客服设置', 'ri:equalizer-2-line', 58, TRUE, TRUE, TRUE, FALSE);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (851, 16008),
    (851, 16009),
    (851, 16011),
    (852, 16009),
    (852, 16010),
    (852, 16011);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, menu_item.menu_id
FROM admin_role role_item
CROSS JOIN (
    SELECT 850 AS menu_id
    UNION ALL SELECT 851
    UNION ALL SELECT 852
) menu_item
WHERE role_item.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE_MANAGER')
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_item.id
        AND existing.menu_id = menu_item.menu_id
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE_MANAGER')
  AND permission_item.id BETWEEN 16008 AND 16012
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = permission_item.id
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
  AND permission_item.id = 16001
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = permission_item.id
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, menu_item.menu_id
FROM admin_role role_item
CROSS JOIN (
    SELECT 830 AS menu_id
    UNION ALL SELECT 840
) menu_item
WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_item.id
        AND existing.menu_id = menu_item.menu_id
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 840, permission_item.id
FROM admin_permission permission_item
WHERE permission_item.id = 16012
  AND NOT EXISTS (
      SELECT 1
      FROM admin_menu_permission existing
      WHERE existing.menu_id = 840
        AND existing.permission_id = permission_item.id
  );
