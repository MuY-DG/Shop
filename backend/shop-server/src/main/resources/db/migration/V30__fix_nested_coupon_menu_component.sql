UPDATE admin_menu
SET component = '',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 401
  AND parent_id = 400
  AND name = 'MarketingCouponCenter'
  AND component = '/index/index';
