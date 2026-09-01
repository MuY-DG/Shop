ALTER TABLE admin_system_log
  ADD COLUMN related_target_type varchar(64) NOT NULL DEFAULT '' AFTER target_id;

ALTER TABLE admin_system_log
  ADD COLUMN related_target_id varchar(128) NOT NULL DEFAULT '' AFTER related_target_type;

ALTER TABLE admin_system_log
  ADD COLUMN provider_error_code varchar(64) NOT NULL DEFAULT '' AFTER error_code;

CREATE INDEX idx_admin_system_log_related_target
  ON admin_system_log (related_target_type,related_target_id,occurred_at);

CREATE INDEX idx_admin_system_log_provider_error
  ON admin_system_log (provider_error_code,occurred_at);

CREATE TABLE refund_provider_attempt (
  id bigint NOT NULL AUTO_INCREMENT,
  refund_order_id bigint DEFAULT NULL,
  after_sale_id bigint NOT NULL,
  order_id bigint NOT NULL,
  out_trade_no varchar(64) NOT NULL DEFAULT '',
  out_refund_no varchar(64) NOT NULL DEFAULT '',
  attempt_type varchar(32) NOT NULL,
  source varchar(20) NOT NULL,
  result varchar(20) NOT NULL,
  provider_http_status int DEFAULT NULL,
  provider_error_code varchar(64) NOT NULL DEFAULT '',
  provider_status varchar(32) NOT NULL DEFAULT '',
  decision varchar(48) NOT NULL,
  request_id varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_refund_provider_attempt_refund_created (refund_order_id,created_at,id),
  INDEX idx_refund_provider_attempt_after_sale_created (after_sale_id,created_at,id),
  INDEX idx_refund_provider_attempt_order_created (order_id,created_at,id),
  INDEX idx_refund_provider_attempt_error_created (provider_error_code,created_at,id),
  INDEX idx_refund_provider_attempt_request (request_id)
);
