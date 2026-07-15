ALTER TABLE customer_service_conversation
    ADD COLUMN consultation_no INT NOT NULL DEFAULT 1;

ALTER TABLE customer_service_conversation
    ADD COLUMN context_type VARCHAR(20) NOT NULL DEFAULT 'GENERAL';

ALTER TABLE customer_service_conversation
    ADD COLUMN context_id BIGINT NULL;

ALTER TABLE customer_service_message
    ADD COLUMN consultation_no INT NOT NULL DEFAULT 1;

ALTER TABLE customer_service_message
    ADD COLUMN resource_id BIGINT NULL;

CREATE TABLE customer_service_consultation_resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    consultation_no INT NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    resource_id BIGINT NOT NULL,
    added_by_type VARCHAR(20) NOT NULL,
    added_by_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_customer_service_consultation_resource
    ON customer_service_consultation_resource(
        conversation_id, consultation_no, resource_type, resource_id
    );

CREATE INDEX idx_customer_service_consultation_resource_lookup
    ON customer_service_consultation_resource(resource_type, resource_id, conversation_id);

INSERT INTO customer_service_consultation_resource (
    conversation_id, consultation_no, resource_type, resource_id,
    added_by_type, added_by_id, created_at
)
SELECT conversation_id, 1, 'ORDER', order_id,
       linked_by_type, linked_by_id, created_at
FROM customer_service_conversation_order;

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (16007, 'customer-service:product:send', 'Send product cards in customer service');

UPDATE admin_menu
SET parent_id = 830,
    path = 'customer-service',
    sort_order = 53
WHERE id = 840;

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM admin_role r
CROSS JOIN admin_menu m
WHERE r.code = 'R_CUSTOMER_SERVICE'
  AND m.id IN (830, 501, 821, 840)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = r.id AND existing.menu_id = m.id
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code = 'R_CUSTOMER_SERVICE'
  AND p.auth_mark IN (
      'order:read',
      'aftersale:read',
      'customer-service:product:send'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code = 'R_SUPER'
  AND p.auth_mark = 'customer-service:product:send'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 840, p.id
FROM admin_permission p
WHERE p.auth_mark = 'customer-service:product:send';
