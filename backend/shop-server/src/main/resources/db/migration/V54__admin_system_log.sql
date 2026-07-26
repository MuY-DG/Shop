CREATE TABLE admin_system_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    log_type VARCHAR(20) NOT NULL,
    result VARCHAR(20) NOT NULL,
    level VARCHAR(10) NOT NULL,
    operator_id BIGINT NULL,
    operator_name VARCHAR(64) NOT NULL DEFAULT '',
    module VARCHAR(64) NOT NULL DEFAULT '',
    action VARCHAR(128) NOT NULL DEFAULT '',
    request_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    route_pattern VARCHAR(255) NOT NULL DEFAULT '',
    http_status INT NOT NULL,
    duration_ms BIGINT NOT NULL,
    client_ip VARCHAR(45) NOT NULL,
    user_agent VARCHAR(255) NOT NULL DEFAULT '',
    request_id VARCHAR(128) NOT NULL,
    error_code VARCHAR(64) NOT NULL DEFAULT '',
    error_message VARCHAR(255) NOT NULL DEFAULT '',
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_admin_system_log_occurred_id
    ON admin_system_log(occurred_at, id);

CREATE INDEX idx_admin_system_log_type_result_occurred
    ON admin_system_log(log_type, result, occurred_at);

CREATE INDEX idx_admin_system_log_module_occurred
    ON admin_system_log(module, occurred_at);

CREATE INDEX idx_admin_system_log_operator_occurred
    ON admin_system_log(operator_id, occurred_at);

CREATE INDEX idx_admin_system_log_client_ip_occurred
    ON admin_system_log(client_ip, occurred_at);

CREATE INDEX idx_admin_system_log_request_id
    ON admin_system_log(request_id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (1300, 'system:log:read', 'Read system logs');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    204, 200, 'SystemLog', 'log', '/system/log', 'menus.system.log', 'ri:file-list-3-line',
    94, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT id, 204
FROM admin_role
WHERE code = 'R_SUPER';

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT id, 1300
FROM admin_role
WHERE code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (204, 1300);
