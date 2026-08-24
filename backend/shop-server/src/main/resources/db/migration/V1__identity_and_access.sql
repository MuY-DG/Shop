-- Shop schema generation 2 baseline.
-- Identity, administrator RBAC, users, and audit foundation.

CREATE TABLE admin_menu (
  id bigint NOT NULL,
  parent_id bigint DEFAULT NULL,
  name varchar(64) NOT NULL,
  path varchar(128) NOT NULL,
  component varchar(128) NOT NULL,
  title varchar(128) NOT NULL,
  icon varchar(64) NOT NULL DEFAULT '',
  sort_order int NOT NULL DEFAULT '0',
  keep_alive BOOLEAN NOT NULL DEFAULT FALSE,
  visible BOOLEAN NOT NULL DEFAULT TRUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  full_page BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id),
  INDEX idx_admin_menu_parent_sort (parent_id,sort_order)
);

CREATE TABLE admin_menu_permission (
  menu_id bigint NOT NULL,
  permission_id bigint NOT NULL,
  PRIMARY KEY (menu_id,permission_id)
);

CREATE TABLE admin_permission (
  id bigint NOT NULL,
  auth_mark varchar(128) NOT NULL,
  title varchar(64) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_admin_permission_auth_mark UNIQUE (auth_mark)
);

CREATE TABLE admin_registration_setting (
  id bigint NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  updated_by_admin_user_id bigint DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_admin_registration_setting_singleton CHECK ((id = 1))
);

CREATE TABLE admin_role (
  id bigint NOT NULL AUTO_INCREMENT,
  code varchar(64) NOT NULL,
  name varchar(64) NOT NULL,
  description varchar(255) NOT NULL DEFAULT '',
  enabled BOOLEAN NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_admin_role_code UNIQUE (code)
);

CREATE TABLE admin_role_menu (
  role_id bigint NOT NULL,
  menu_id bigint NOT NULL,
  PRIMARY KEY (role_id,menu_id)
);

CREATE TABLE admin_role_permission (
  role_id bigint NOT NULL,
  permission_id bigint NOT NULL,
  PRIMARY KEY (role_id,permission_id)
);

CREATE TABLE admin_system_log (
  id bigint NOT NULL AUTO_INCREMENT,
  log_type varchar(20) NOT NULL,
  result varchar(20) NOT NULL,
  level varchar(10) NOT NULL,
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
  INDEX idx_admin_system_log_module_occurred (module,occurred_at),
  INDEX idx_admin_system_log_operator_occurred (operator_id,occurred_at),
  INDEX idx_admin_system_log_client_ip_occurred (client_ip,occurred_at),
  INDEX idx_admin_system_log_request_id (request_id)
);

CREATE TABLE admin_user (
  id bigint NOT NULL AUTO_INCREMENT,
  username varchar(64) NOT NULL,
  password_hash varchar(128) NOT NULL,
  display_name varchar(64) NOT NULL,
  email varchar(128) NOT NULL,
  avatar varchar(255) NOT NULL DEFAULT '',
  status varchar(20) NOT NULL,
  last_login_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  max_sessions int NOT NULL DEFAULT '0',
  auth_version bigint NOT NULL DEFAULT '0',
  username_normalized varchar(64) DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_admin_user_username UNIQUE (username),
  CONSTRAINT uk_admin_user_username_normalized UNIQUE (username_normalized),
  CONSTRAINT chk_admin_user_max_sessions CHECK ((max_sessions >= 0))
);

CREATE TABLE admin_user_role (
  user_id bigint NOT NULL,
  role_id bigint NOT NULL,
  PRIMARY KEY (user_id,role_id)
);

CREATE TABLE app_user (
  id bigint NOT NULL,
  openid varchar(128) NOT NULL,
  unionid varchar(128) DEFAULT NULL,
  phone_number varchar(32) DEFAULT NULL,
  phone_country_code varchar(16) DEFAULT NULL,
  phone_authorized BOOLEAN NOT NULL DEFAULT FALSE,
  status varchar(20) NOT NULL,
  last_login_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  nickname varchar(64) NOT NULL DEFAULT '',
  phone_authorized_at timestamp NULL DEFAULT NULL,
  avatar_url varchar(1024) DEFAULT NULL,
  auth_version bigint NOT NULL DEFAULT '0',
  cancelled_at timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_app_user_openid UNIQUE (openid),
  INDEX idx_app_user_phone (phone_number),
  INDEX idx_app_user_statistics_created (created_at,id),
  INDEX idx_app_user_phone_authorized_at (phone_authorized_at,id)
);

CREATE TABLE app_user_avatar_daily_limit (
  user_id bigint NOT NULL,
  limit_date date NOT NULL,
  change_count int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id,limit_date),
  INDEX idx_app_user_avatar_daily_limit_date (limit_date,user_id)
);

CREATE TABLE app_user_daily_activity (
  user_id bigint NOT NULL,
  activity_date date NOT NULL,
  first_active_at timestamp NOT NULL,
  last_active_at timestamp NOT NULL,
  request_count bigint NOT NULL DEFAULT '1',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id,activity_date),
  INDEX idx_app_user_daily_activity_date (activity_date,user_id)
);

CREATE TABLE system_health_marker (
  id bigint NOT NULL,
  marker_key varchar(64) NOT NULL,
  marker_value varchar(128) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE TABLE user_address (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL,
  receiver_name varchar(64) NOT NULL,
  receiver_phone varchar(32) NOT NULL,
  province varchar(64) NOT NULL,
  city varchar(64) NOT NULL,
  district varchar(64) NOT NULL,
  detail_address varchar(255) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  location_name varchar(128) NOT NULL DEFAULT '',
  doorplate varchar(128) NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  INDEX idx_user_address_user_default (user_id,is_default,id)
);

CREATE TABLE app_user_status_change_audit (
  id bigint NOT NULL,
  user_id bigint NOT NULL,
  admin_user_id bigint NOT NULL,
  from_status varchar(20) NOT NULL,
  to_status varchar(20) NOT NULL,
  reason varchar(200) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX fk_app_user_status_change_admin (admin_user_id),
  INDEX idx_app_user_status_change_user_time (user_id,created_at,id),
  CONSTRAINT fk_app_user_status_change_admin FOREIGN KEY (admin_user_id) REFERENCES admin_user (id),
  CONSTRAINT fk_app_user_status_change_user FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT chk_app_user_status_change_from CHECK ((from_status in ('ENABLED','DISABLED'))),
  CONSTRAINT chk_app_user_status_change_to CHECK ((to_status in ('ENABLED','DISABLED'))),
  CONSTRAINT chk_app_user_status_change_transition CHECK ((from_status <> to_status))
);
