CREATE TABLE analytics_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    client_event_id VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    visitor_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NULL,
    event_source VARCHAR(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    page_path VARCHAR(160) NOT NULL DEFAULT '',
    source_page VARCHAR(160) NOT NULL DEFAULT '',
    entry_scene VARCHAR(32) NOT NULL DEFAULT '',
    search_keyword VARCHAR(80) NOT NULL DEFAULT '',
    checkout_source VARCHAR(20) NOT NULL DEFAULT '',
    spu_id BIGINT NULL,
    sku_id BIGINT NULL,
    quantity INT NULL,
    occurred_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    business_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_analytics_event_visitor_client UNIQUE (visitor_id, client_event_id)
);

CREATE INDEX idx_analytics_event_date_type
    ON analytics_event(business_date, event_type, id);
CREATE INDEX idx_analytics_event_user_date
    ON analytics_event(user_id, business_date, id);
CREATE INDEX idx_analytics_event_session_time
    ON analytics_event(visitor_id, session_id, occurred_at, id);
CREATE INDEX idx_analytics_event_spu_type_date
    ON analytics_event(spu_id, event_type, business_date, id);
CREATE INDEX idx_analytics_event_received
    ON analytics_event(received_at, id);

CREATE TABLE app_user_daily_activity (
    user_id BIGINT NOT NULL,
    activity_date DATE NOT NULL,
    first_active_at TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, activity_date)
);

CREATE INDEX idx_app_user_daily_activity_date
    ON app_user_daily_activity(activity_date, user_id);

ALTER TABLE app_user
    ADD COLUMN phone_authorized_at TIMESTAMP NULL;

UPDATE app_user
SET phone_authorized_at = CURRENT_TIMESTAMP
WHERE phone_authorized = TRUE
  AND phone_authorized_at IS NULL;

CREATE INDEX idx_app_user_phone_authorized_at
    ON app_user(phone_authorized_at, id);

CREATE TABLE payment_attempt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_order_id BIGINT NULL,
    out_trade_no VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount_cent BIGINT NOT NULL,
    error_code VARCHAR(64) NOT NULL DEFAULT '',
    error_message VARCHAR(255) NOT NULL DEFAULT '',
    started_at TIMESTAMP NOT NULL,
    prepay_succeeded_at TIMESTAMP NULL,
    paid_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_payment_attempt_payment_order UNIQUE (payment_order_id)
);

CREATE INDEX idx_payment_attempt_status_started
    ON payment_attempt(status, started_at, id);
CREATE INDEX idx_payment_attempt_order_started
    ON payment_attempt(order_id, started_at, id);
CREATE INDEX idx_payment_attempt_out_trade_started
    ON payment_attempt(out_trade_no, started_at, id);
CREATE INDEX idx_payment_attempt_started
    ON payment_attempt(started_at, id);

ALTER TABLE order_item
    ADD COLUMN unit_cost_cent BIGINT NULL;
ALTER TABLE order_item
    ADD COLUMN line_cost_cent BIGINT NULL;
ALTER TABLE order_item
    ADD COLUMN coupon_discount_allocated_cent BIGINT NULL;
ALTER TABLE order_item
    ADD COLUMN freight_allocated_cent BIGINT NULL;
ALTER TABLE order_item
    ADD COLUMN paid_amount_allocated_cent BIGINT NULL;

ALTER TABLE shop_order
    ADD COLUMN analytics_visitor_id VARCHAR(64) NULL;
ALTER TABLE shop_order
    ADD COLUMN analytics_session_id VARCHAR(64) NULL;
ALTER TABLE shop_order
    ADD COLUMN analytics_entry_scene VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE product_sku
    ADD COLUMN low_stock_threshold INT NOT NULL DEFAULT 10;

CREATE INDEX idx_shop_order_paid_at
    ON shop_order(paid_at, id);
CREATE INDEX idx_shop_order_user_paid_at
    ON shop_order(user_id, paid_at, id);
CREATE INDEX idx_shop_order_analytics_session
    ON shop_order(analytics_visitor_id, analytics_session_id, id);
CREATE INDEX idx_payment_order_status_paid_at
    ON payment_order(status, paid_at, id);
CREATE INDEX idx_refund_order_status_success_at
    ON refund_order(status, success_at, id);
CREATE INDEX idx_order_item_sku_order
    ON order_item(sku_id, order_id);
CREATE INDEX idx_cart_item_updated
    ON cart_item(updated_at, id);
CREATE INDEX idx_user_coupon_template_status_used
    ON user_coupon(template_id, status, used_at, id);
CREATE INDEX idx_coupon_claim_claimed
    ON coupon_claim_record(claimed_at, id);
CREATE INDEX idx_stock_log_created_sku
    ON stock_log(created_at, sku_id, id);
CREATE INDEX idx_product_sku_low_stock
    ON product_sku(deleted_at, status, stock_available, low_stock_threshold, id);
CREATE INDEX idx_after_sale_statistics_reviewed
    ON after_sale_request(reviewed_at, status, id);
CREATE INDEX idx_customer_service_message_consultation_created
    ON customer_service_message(conversation_id, consultation_no, created_at, sender_type);
CREATE INDEX idx_customer_service_assignment_action_created
    ON customer_service_assignment_log(conversation_id, action, created_at, id);
