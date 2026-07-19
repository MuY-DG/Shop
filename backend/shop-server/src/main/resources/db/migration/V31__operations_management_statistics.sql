UPDATE admin_menu
SET name = 'Operations',
    path = '/operations',
    component = '/index/index',
    title = '运营管理',
    icon = 'ri:bar-chart-box-line',
    sort_order = 10,
    keep_alive = FALSE,
    visible = TRUE,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 100;

UPDATE admin_menu
SET parent_id = 100,
    name = 'OperationsOverview',
    path = 'overview',
    component = '/operations/overview',
    title = '运营总览',
    icon = 'ri:dashboard-line',
    sort_order = 11,
    keep_alive = TRUE,
    visible = TRUE,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 101;

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES
    (102, 100, 'OperationsTradeStatistics', 'trade-statistics', '/operations/trade-statistics',
     '交易统计', 'ri:exchange-funds-line', 12, TRUE, TRUE, TRUE),
    (103, 100, 'OperationsProductStatistics', 'product-statistics', '/operations/product-statistics',
     '商品统计', 'ri:shopping-bag-3-line', 13, TRUE, TRUE, TRUE),
    (104, 100, 'OperationsUserStatistics', 'user-statistics', '/operations/user-statistics',
     '用户统计', 'ri:user-heart-line', 14, TRUE, TRUE, TRUE),
    (105, 100, 'OperationsTrafficStatistics', 'traffic-statistics', '/operations/traffic-statistics',
     '流量转化', 'ri:funnel-line', 15, TRUE, TRUE, TRUE),
    (106, 100, 'OperationsMarketingStatistics', 'marketing-statistics', '/operations/marketing-statistics',
     '营销统计', 'ri:coupon-3-line', 16, TRUE, TRUE, TRUE),
    (107, 100, 'OperationsServiceStatistics', 'service-statistics', '/operations/service-statistics',
     '服务统计', 'ri:customer-service-2-line', 17, TRUE, TRUE, TRUE);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (17001, 'operation:overview:read', 'Read operations overview'),
    (17002, 'operation:trade:read', 'Read trade statistics'),
    (17003, 'operation:product:read', 'Read product statistics'),
    (17004, 'operation:user:read', 'Read user statistics'),
    (17005, 'operation:traffic:read', 'Read traffic statistics'),
    (17006, 'operation:marketing:read', 'Read marketing statistics'),
    (17007, 'operation:service:read', 'Read service statistics');

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (101, 17001),
    (102, 17002),
    (103, 17003),
    (104, 17004),
    (105, 17005),
    (106, 17006),
    (107, 17007);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT existing.role_id, 17001
FROM admin_role_menu existing
WHERE existing.menu_id = 101
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission grant_row
      WHERE grant_row.role_id = existing.role_id
        AND grant_row.permission_id = 17001
  );

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT r.id, child.menu_id
FROM admin_role r
CROSS JOIN (
    SELECT 102 AS menu_id
    UNION ALL SELECT 103
    UNION ALL SELECT 104
    UNION ALL SELECT 105
    UNION ALL SELECT 106
    UNION ALL SELECT 107
) child
WHERE r.code = 'R_SUPER'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = r.id
        AND existing.menu_id = child.menu_id
  );

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code = 'R_SUPER'
  AND p.id BETWEEN 17002 AND 17007
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );

CREATE INDEX idx_shop_order_statistics_paid
    ON shop_order(paid_at, user_id, id);

CREATE INDEX idx_refund_order_statistics_success
    ON refund_order(success_at, status, order_id);

CREATE INDEX idx_payment_order_statistics_created
    ON payment_order(created_at, status, order_id);

CREATE INDEX idx_order_item_statistics_spu_order
    ON order_item(spu_id, order_id, sku_id);

CREATE INDEX idx_app_user_statistics_created
    ON app_user(created_at, id);

CREATE INDEX idx_user_coupon_statistics_claimed
    ON user_coupon(claimed_at, status, template_id);

CREATE INDEX idx_user_coupon_statistics_used
    ON user_coupon(used_at, template_id, user_id);

CREATE INDEX idx_order_shipment_statistics_shipped
    ON order_shipment(shipped_at, wechat_upload_status, order_id);

CREATE INDEX idx_after_sale_statistics_created
    ON after_sale_request(created_at, status, order_id);

CREATE INDEX idx_customer_service_statistics_activated
    ON customer_service_conversation(activated_at, status, id);

CREATE INDEX idx_product_sku_statistics_stock
    ON product_sku(status, deleted_at, stock_available, spu_id);
