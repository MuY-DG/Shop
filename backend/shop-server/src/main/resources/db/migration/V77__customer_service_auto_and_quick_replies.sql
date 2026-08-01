CREATE TABLE customer_service_auto_reply_config (
    id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    opening_message VARCHAR(2000) NOT NULL DEFAULT '',
    offline_message VARCHAR(2000) NOT NULL DEFAULT '',
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_auto_reply_config_singleton CHECK (id = 1),
    CONSTRAINT chk_customer_service_auto_reply_revision CHECK (revision >= 0)
);

INSERT INTO customer_service_auto_reply_config (
    id, revision, opening_message, offline_message
)
VALUES (1, 0, '', '');

ALTER TABLE customer_service_agent_profile
    ADD COLUMN welcome_message VARCHAR(2000) NOT NULL DEFAULT '';

CREATE TABLE customer_service_common_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question VARCHAR(200) NOT NULL,
    answer VARCHAR(2000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_common_question_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_customer_service_common_question_order
    ON customer_service_common_question(enabled, sort_order, id);

CREATE TABLE customer_service_smart_reply_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    reply_content VARCHAR(2000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_smart_reply_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_customer_service_smart_reply_group_order
    ON customer_service_smart_reply_group(enabled, sort_order, id);

CREATE TABLE customer_service_smart_reply_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reply_group_id BIGINT NOT NULL,
    question VARCHAR(200) NOT NULL,
    normalized_question VARCHAR(200) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_smart_reply_question_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_customer_service_smart_reply_question_group
    ON customer_service_smart_reply_question(reply_group_id, sort_order, id);

CREATE INDEX idx_customer_service_smart_reply_question_normalized
    ON customer_service_smart_reply_question(normalized_question);

CREATE TABLE customer_service_quick_reply_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_quick_reply_group_sort CHECK (sort_order >= 0)
);

INSERT INTO customer_service_quick_reply_group (id, name, sort_order)
VALUES (1, '默认分组', 0);

CREATE TABLE customer_service_quick_reply (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_service_quick_reply_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_customer_service_quick_reply_group_order
    ON customer_service_quick_reply(group_id, sort_order, id);

CREATE TABLE customer_service_offline_reply_state (
    app_user_id BIGINT PRIMARY KEY,
    last_replied_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE customer_service_message
    ADD COLUMN automation_key VARCHAR(128) NULL;

CREATE UNIQUE INDEX uk_customer_service_message_automation
    ON customer_service_message(conversation_id, consultation_no, automation_key);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (16014, 'customer-service:auto-reply:read', '查看客服自动回复'),
    (16015, 'customer-service:auto-reply:welcome:update', '修改个人接入欢迎语'),
    (16016, 'customer-service:auto-reply:update', '修改公共自动回复'),
    (16017, 'customer-service:quick-reply:read', '查看客服快捷回复'),
    (16018, 'customer-service:quick-reply:update', '修改公共快捷回复');

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (842, 16014),
    (842, 16015),
    (842, 16016),
    (842, 16017),
    (842, 16018),
    (840, 16017);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code IN (
        'R_CUSTOMER_SERVICE', 'R_CUSTOMER_SERVICE_MANAGER', 'R_SUPER'
      )
  AND permission_item.id IN (16014, 16017)
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
WHERE role_item.code = 'R_CUSTOMER_SERVICE'
  AND permission_item.id = 16015
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
  AND permission_item.id IN (16016, 16018)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = role_item.id
        AND existing.permission_id = permission_item.id
  );
