CREATE TABLE customer_service_agent_state (
    admin_user_id BIGINT PRIMARY KEY,
    work_status VARCHAR(20) NOT NULL DEFAULT 'BUSY',
    max_active_conversations INT NOT NULL DEFAULT 5,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE customer_service_transfer_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    from_admin_user_id BIGINT NOT NULL,
    to_admin_user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    reason_note VARCHAR(200) NULL,
    pending_key INT NULL,
    expires_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_customer_service_transfer_pending
    ON customer_service_transfer_request(conversation_id, pending_key);

CREATE INDEX idx_customer_service_transfer_target
    ON customer_service_transfer_request(to_admin_user_id, status, expires_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (16008, 'customer-service:agent:manage', 'Manage customer service agent assignment');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code = 'R_SUPER'
  AND p.auth_mark = 'customer-service:agent:manage'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 840, p.id
FROM admin_permission p
WHERE p.auth_mark = 'customer-service:agent:manage';
