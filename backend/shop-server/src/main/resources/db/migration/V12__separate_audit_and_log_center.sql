-- The previous log table intentionally contains only disposable diagnostics.
-- Rebuild it instead of carrying noisy access rows or a legacy shape forward.

DROP TABLE admin_system_log;

CREATE TABLE admin_system_log (
  id bigint NOT NULL AUTO_INCREMENT,
  log_type varchar(20) NOT NULL,
  result varchar(20) NOT NULL,
  level varchar(10) NOT NULL,
  event_code varchar(128) NOT NULL DEFAULT '',
  summary varchar(255) NOT NULL DEFAULT '',
  target_type varchar(64) NOT NULL DEFAULT '',
  target_id varchar(128) NOT NULL DEFAULT '',
  operator_id bigint DEFAULT NULL,
  operator_name varchar(64) NOT NULL DEFAULT '',
  module varchar(64) NOT NULL DEFAULT '',
  action varchar(128) NOT NULL DEFAULT '',
  request_method varchar(10) NOT NULL,
  request_path varchar(255) NOT NULL,
  route_pattern varchar(255) NOT NULL DEFAULT '',
  http_status int NOT NULL,
  duration_ms bigint NOT NULL,
  client_ip varchar(45) NOT NULL,
  user_agent varchar(255) NOT NULL DEFAULT '',
  request_id varchar(128) NOT NULL,
  error_code varchar(64) NOT NULL DEFAULT '',
  error_message varchar(255) NOT NULL DEFAULT '',
  occurred_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_admin_system_log_occurred_id (occurred_at,id),
  INDEX idx_admin_system_log_type_result_occurred (log_type,result,occurred_at),
  INDEX idx_admin_system_log_event_occurred (event_code,occurred_at),
  INDEX idx_admin_system_log_module_occurred (module,occurred_at),
  INDEX idx_admin_system_log_operator_occurred (operator_id,occurred_at),
  INDEX idx_admin_system_log_client_ip_occurred (client_ip,occurred_at),
  INDEX idx_admin_system_log_request_id (request_id),
  INDEX idx_admin_system_log_target (target_type,target_id,occurred_at)
);

-- Promote the former system-log page into a dedicated audit and log center.
-- Existing permission id 1300 remains the single read boundary and all new
-- menu entries inherit the exact roles that could previously open menu 204.

UPDATE admin_menu
SET parent_id = NULL,
    name = 'AuditLog',
    path = '/audit-log',
    component = '/index/index',
    title = 'menus.auditLog.title',
    icon = 'ri:file-shield-2-line',
    sort_order = 89,
    keep_alive = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 204;

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon, sort_order,
    keep_alive, visible, enabled, created_at, updated_at, full_page
) VALUES
    (205,204,'AuditOperation','operation','/system/log','menus.auditLog.operation','ri:shield-check-line',891,TRUE,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE),
    (206,204,'AuditSecurity','security','/system/log','menus.auditLog.security','ri:login-box-line',892,TRUE,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE),
    (207,204,'AuditException','exceptions','/system/log','menus.auditLog.exception','ri:alarm-warning-line',893,TRUE,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE),
    (208,204,'AuditRequest','requests','/system/log','menus.auditLog.request','ri:route-line',894,TRUE,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE),
    (209,204,'AuditTask','tasks','/system/task-log','menus.auditLog.task','ri:timer-flash-line',895,TRUE,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_id, 205 FROM admin_role_menu WHERE menu_id = 204;
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_id, 206 FROM admin_role_menu WHERE menu_id = 204;
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_id, 207 FROM admin_role_menu WHERE menu_id = 204;
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_id, 208 FROM admin_role_menu WHERE menu_id = 204;
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_id, 209 FROM admin_role_menu WHERE menu_id = 204;

DELETE FROM admin_menu_permission
WHERE menu_id = 204 AND permission_id = 1300;

INSERT INTO admin_menu_permission (menu_id, permission_id) VALUES
    (205,1300),
    (206,1300),
    (207,1300),
    (208,1300),
    (209,1300),
    (209,18005);
