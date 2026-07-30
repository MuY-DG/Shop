UPDATE customer_service_agent_state
SET work_status = 'OFFLINE',
    updated_at = CURRENT_TIMESTAMP
WHERE work_status = 'BUSY';

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled, full_page
)
VALUES
    (841, 840, 'CustomerServiceOverview', 'overview', '/customer-service/overview',
     '概况', 'ri:line-chart-line', 56, TRUE, TRUE, TRUE, TRUE),
    (842, 840, 'CustomerServiceSettings', 'settings', '/customer-service/settings',
     '设置', 'ri:settings-3-line', 57, TRUE, TRUE, TRUE, TRUE);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (841, 16001),
    (842, 16009),
    (842, 16010),
    (842, 16011);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, menu_item.menu_id
FROM admin_role role_item
CROSS JOIN (
    SELECT 841 AS menu_id
    UNION ALL SELECT 842
) menu_item
WHERE (
        menu_item.menu_id = 841
        AND role_item.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE', 'R_CUSTOMER_SERVICE_MANAGER')
    )
   OR (
        menu_item.menu_id = 842
        AND role_item.code IN ('R_SUPER', 'R_CUSTOMER_SERVICE_MANAGER')
    );

DELETE FROM admin_role_menu
WHERE menu_id IN (850, 851, 852)
  AND role_id IN (
      SELECT role_item.id
      FROM admin_role role_item
      WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
  );

DELETE FROM admin_role_permission
WHERE permission_id = 16008
  AND role_id IN (
      SELECT role_item.id
      FROM admin_role role_item
      WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
  );

UPDATE admin_menu
SET visible = FALSE,
    enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 852;
