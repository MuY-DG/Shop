INSERT INTO admin_permission (id, auth_mark, title)
VALUES (3005, 'coupon:claim:read', 'Read coupon claim records');

UPDATE admin_menu
SET name = 'MarketingCouponCenter',
    component = '/index/index',
    keep_alive = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 401;

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES
    (402, 401, 'MarketingCoupon', 'templates', '/marketing/coupon',
     '优惠券', 'ri:coupon-line', 41, TRUE, TRUE, TRUE),
    (403, 401, 'MarketingCouponClaim', 'claim-records', '/marketing/coupon-claim',
     '领取记录', 'ri:file-list-3-line', 42, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT existing.role_id, 402
FROM admin_role_menu existing
WHERE existing.menu_id = 401
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu child
      WHERE child.role_id = existing.role_id
        AND child.menu_id = 402
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT existing.role_id, 403
FROM admin_role_menu existing
WHERE existing.menu_id = 401
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu child
      WHERE child.role_id = existing.role_id
        AND child.menu_id = 403
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT existing.role_id, 3005
FROM admin_role_menu existing
WHERE existing.menu_id = 401
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission grant_row
      WHERE grant_row.role_id = existing.role_id
        AND grant_row.permission_id = 3005
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 402, existing.permission_id
FROM admin_menu_permission existing
WHERE existing.menu_id = 401;

DELETE FROM admin_menu_permission
WHERE menu_id = 401;

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (403, 3005);
