CREATE TABLE customer_service_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    assigned_admin_user_id BIGINT NULL,
    last_message_at TIMESTAMP NULL,
    app_unread_count INT NOT NULL DEFAULT 0,
    admin_unread_count INT NOT NULL DEFAULT 0,
    claimed_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_customer_service_conversation_user
    ON customer_service_conversation(app_user_id);

CREATE INDEX idx_customer_service_conversation_queue
    ON customer_service_conversation(status, last_message_at, id);

CREATE TABLE customer_service_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sender_type VARCHAR(20) NOT NULL,
    sender_id BIGINT NULL,
    message_type VARCHAR(20) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    client_message_id VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customer_service_message_conversation
    ON customer_service_message(conversation_id, id);

CREATE UNIQUE INDEX uk_customer_service_message_client
    ON customer_service_message(conversation_id, sender_type, sender_id, client_message_id);

CREATE TABLE customer_service_assignment_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    from_admin_user_id BIGINT NULL,
    to_admin_user_id BIGINT NULL,
    operator_type VARCHAR(20) NOT NULL,
    operator_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customer_service_assignment_conversation
    ON customer_service_assignment_log(conversation_id, id);

CREATE TABLE customer_service_conversation_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    linked_by_type VARCHAR(20) NOT NULL,
    linked_by_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_customer_service_conversation_order
    ON customer_service_conversation_order(conversation_id, order_id);

CREATE INDEX idx_customer_service_conversation_order_order
    ON customer_service_conversation_order(order_id, conversation_id);

INSERT INTO admin_role (code, name, description, enabled)
SELECT 'R_CUSTOMER_SERVICE', 'Customer Service', 'Online customer service agent', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM admin_role WHERE code = 'R_CUSTOMER_SERVICE'
);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (16001, 'customer-service:conversation:read', 'Read customer service conversations'),
    (16002, 'customer-service:conversation:claim', 'Claim customer service conversations'),
    (16003, 'customer-service:conversation:transfer', 'Transfer customer service conversations'),
    (16004, 'customer-service:conversation:close', 'Close customer service conversations'),
    (16005, 'customer-service:message:send', 'Send customer service messages'),
    (16006, 'customer-service:order:link', 'Link orders to customer service conversations');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    840, NULL, 'CustomerService', '/customer-service', '/customer-service/index',
    '在线客服', 'ri:customer-service-2-line', 55, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT r.id, 840
FROM admin_role r
WHERE r.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE')
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = r.id AND existing.menu_id = 840
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE')
  AND p.id BETWEEN 16001 AND 16006
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 840, p.id
FROM admin_permission p
WHERE p.id BETWEEN 16001 AND 16006;
