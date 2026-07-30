UPDATE admin_menu
SET parent_id = NULL,
    path = '/customer-service',
    sort_order = 55,
    full_page = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 840;

DELETE FROM admin_role_menu
WHERE menu_id = 830
  AND role_id IN (
      SELECT role_item.id
      FROM admin_role role_item
      WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
  );
