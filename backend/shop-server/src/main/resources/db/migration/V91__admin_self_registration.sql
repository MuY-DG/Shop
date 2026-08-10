ALTER TABLE admin_user
    ADD COLUMN username_normalized VARCHAR(64) NULL;

UPDATE admin_user
SET username_normalized = LOWER(TRIM(username))
WHERE username_normalized IS NULL;

CREATE UNIQUE INDEX uk_admin_user_username_normalized
    ON admin_user(username_normalized);

CREATE TABLE admin_registration_setting (
    id BIGINT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by_admin_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_admin_registration_setting_singleton CHECK (id = 1)
);

INSERT INTO admin_registration_setting (
    id, enabled, updated_by_admin_user_id, created_at, updated_at
) VALUES (
    1, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

UPDATE admin_role
SET enabled = TRUE,
    name = '游客',
    description = '仅可查看系统介绍',
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'R_GUEST';

DELETE FROM admin_role_permission
WHERE role_id IN (
    SELECT id FROM admin_role WHERE code = 'R_GUEST'
);

DELETE FROM admin_role_menu
WHERE role_id IN (
    SELECT id FROM admin_role WHERE code = 'R_GUEST'
)
  AND menu_id <> 860;

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
