-- Permanently remove the retired WeChat 2001 service-card capability.

DELETE FROM admin_system_log
WHERE request_path LIKE '/admin/wechat-service-cards%'
   OR request_path = '/wechat/mini/message'
   OR route_pattern LIKE '/admin/wechat-service-cards%'
   OR route_pattern = '/wechat/mini/message'
   OR module LIKE '%WechatServiceCard%';

DELETE FROM admin_menu_permission
WHERE menu_id = 806
   OR permission_id IN (22001, 22002, 23003, 23004);

DELETE FROM admin_role_permission
WHERE permission_id IN (22001, 22002, 23003, 23004);

DELETE FROM admin_menu
WHERE id = 806
   OR name = 'WechatServiceCard'
   OR component = '/configuration/wechat-service-card';

DELETE FROM admin_permission
WHERE id IN (22001, 22002, 23003, 23004)
   OR auth_mark LIKE 'wechat-service-card:%';

DROP TABLE IF EXISTS wechat_service_card_callback_log;
DROP TABLE IF EXISTS wechat_service_card_delivery;
DROP TABLE IF EXISTS wechat_service_card;
DROP TABLE IF EXISTS wechat_service_card_runtime_audit;
DROP TABLE IF EXISTS wechat_service_card_runtime_setting;
DROP TABLE IF EXISTS wechat_service_card_config_audit;
DROP TABLE IF EXISTS wechat_service_card_config;
