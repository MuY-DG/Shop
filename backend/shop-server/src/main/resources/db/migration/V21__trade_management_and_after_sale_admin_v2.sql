CREATE INDEX idx_after_sale_admin_status_created
    ON after_sale_request(status, created_at, id);

CREATE INDEX idx_after_sale_admin_user_created
    ON after_sale_request(user_id, created_at, id);

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    830, NULL, 'Trade', '/trade', '/index/index', '交易管理', 'ri:exchange-funds-line',
    50, FALSE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT DISTINCT role_menu.role_id, 830
FROM admin_role_menu role_menu
WHERE role_menu.menu_id IN (500, 501, 820, 821)
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_menu.role_id
        AND existing.menu_id = 830
  );

UPDATE admin_menu
SET parent_id = 830,
    path = 'orders',
    sort_order = 51
WHERE id = 501;

UPDATE admin_menu
SET parent_id = 830,
    path = 'after-sales',
    sort_order = 52
WHERE id = 821;

UPDATE admin_menu
SET visible = FALSE,
    enabled = FALSE
WHERE id IN (500, 820);

DELETE FROM admin_role_menu
WHERE menu_id IN (500, 820);
