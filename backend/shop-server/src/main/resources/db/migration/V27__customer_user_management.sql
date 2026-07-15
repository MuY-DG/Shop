ALTER TABLE coupon_claim_record
    ADD COLUMN issue_source VARCHAR(20) NOT NULL DEFAULT 'SELF_CLAIM';

ALTER TABLE coupon_claim_record
    ADD COLUMN issued_by_admin_user_id BIGINT NULL;

ALTER TABLE coupon_claim_record
    ADD COLUMN issue_note VARCHAR(200) NOT NULL DEFAULT '';

CREATE INDEX idx_coupon_claim_issue_source
    ON coupon_claim_record(issue_source, claimed_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (3501, 'customer:user:read', 'Read app customers'),
    (3502, 'customer:coupon:issue', 'Issue coupon to app customer');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    450, NULL, 'CustomerUser', '/customers', '/customer/user',
    '用户管理', 'ri:user-heart-line', 45, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT r.id, 450
FROM admin_role r
WHERE r.code = 'R_SUPER'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = r.id
        AND existing.menu_id = 450
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code = 'R_SUPER'
  AND p.auth_mark IN ('customer:user:read', 'customer:coupon:issue')
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 450, p.id
FROM admin_permission p
WHERE p.auth_mark IN ('customer:user:read', 'customer:coupon:issue');
