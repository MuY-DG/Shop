UPDATE admin_menu
SET icon = 'ri:route-line',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 105
  AND name = 'OperationsTrafficStatistics'
  AND icon = 'ri:funnel-line';
