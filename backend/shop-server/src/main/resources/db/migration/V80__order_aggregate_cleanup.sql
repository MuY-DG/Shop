ALTER TABLE data_cleanup_task_setting
    ADD COLUMN retain_reviews BOOLEAN NULL;

ALTER TABLE data_cleanup_task_setting
    DROP CONSTRAINT chk_data_cleanup_task_code;

ALTER TABLE data_cleanup_task_setting
    DROP CONSTRAINT chk_data_cleanup_retention_days;

ALTER TABLE data_cleanup_task_setting
    DROP CONSTRAINT chk_data_cleanup_batch_size;

ALTER TABLE data_cleanup_task_setting
    DROP CONSTRAINT chk_data_cleanup_upload_grace;

ALTER TABLE data_cleanup_task_setting
    ADD CONSTRAINT chk_data_cleanup_task_code CHECK (
        task_code IN (
            'ANALYTICS_EVENT',
            'ADMIN_SYSTEM_LOG',
            'CUSTOMER_SERVICE_MESSAGE',
            'ORDER_AGGREGATE',
            'STORAGE_ASSET',
            'DIRECT_UPLOAD_SESSION'
        )
    );

ALTER TABLE data_cleanup_task_setting
    ADD CONSTRAINT chk_data_cleanup_retention_days CHECK (
        (task_code = 'ANALYTICS_EVENT'
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 367 AND 3650)
        OR (task_code IN ('ADMIN_SYSTEM_LOG', 'CUSTOMER_SERVICE_MESSAGE')
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 1 AND 3650)
        OR (task_code = 'ORDER_AGGREGATE'
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 1095 AND 3650)
        OR (task_code = 'DIRECT_UPLOAD_SESSION'
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 1 AND 365)
        OR (task_code = 'STORAGE_ASSET' AND retention_days IS NULL)
    );

ALTER TABLE data_cleanup_task_setting
    ADD CONSTRAINT chk_data_cleanup_batch_size CHECK (
        (task_code IN ('ANALYTICS_EVENT', 'ADMIN_SYSTEM_LOG')
            AND batch_size BETWEEN 1 AND 50000)
        OR (task_code = 'CUSTOMER_SERVICE_MESSAGE'
            AND batch_size BETWEEN 1 AND 10000)
        OR (task_code = 'ORDER_AGGREGATE'
            AND batch_size BETWEEN 1 AND 100)
        OR (task_code IN ('STORAGE_ASSET', 'DIRECT_UPLOAD_SESSION')
            AND batch_size BETWEEN 1 AND 1000)
    );

ALTER TABLE data_cleanup_task_setting
    ADD CONSTRAINT chk_data_cleanup_upload_grace CHECK (
        (task_code = 'STORAGE_ASSET'
            AND upload_pending_grace_minutes IS NOT NULL
            AND upload_pending_grace_minutes BETWEEN 5 AND 10080)
        OR (task_code <> 'STORAGE_ASSET' AND upload_pending_grace_minutes IS NULL)
    );

ALTER TABLE data_cleanup_task_setting
    ADD CONSTRAINT chk_data_cleanup_retain_reviews CHECK (
        (task_code = 'ORDER_AGGREGATE' AND retain_reviews IS NOT NULL)
        OR (task_code <> 'ORDER_AGGREGATE' AND retain_reviews IS NULL)
    );

INSERT INTO data_cleanup_task_setting (
    task_code,
    enabled,
    retention_days,
    batch_size,
    cron_expression,
    zone_id,
    batch_interval_seconds,
    upload_pending_grace_minutes,
    retain_reviews
)
VALUES (
    'ORDER_AGGREGATE', TRUE, 1095, 20,
    '0 45 4 * * *', 'Asia/Shanghai', 300, NULL, TRUE
);

ALTER TABLE product_review
    ADD COLUMN source_order_item_id BIGINT NULL;

ALTER TABLE product_review
    ADD COLUMN product_title_snapshot VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE product_review
    ADD COLUMN spec_text_snapshot VARCHAR(255) NOT NULL DEFAULT '';

ALTER TABLE product_review
    ADD COLUMN verified_purchase BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE product_review review
SET source_order_item_id = review.order_item_id,
    product_title_snapshot = COALESCE((
        SELECT item.product_title
        FROM order_item item
        WHERE item.id = review.order_item_id
    ), ''),
    spec_text_snapshot = COALESCE((
        SELECT item.spec_text
        FROM order_item item
        WHERE item.id = review.order_item_id
    ), '');

ALTER TABLE product_review
    MODIFY COLUMN source_order_item_id BIGINT NOT NULL;

ALTER TABLE product_review
    MODIFY COLUMN order_item_id BIGINT NULL;

CREATE UNIQUE INDEX uk_product_review_source_order_item
    ON product_review(source_order_item_id);

ALTER TABLE stock_log
    ADD COLUMN order_id BIGINT NULL;

UPDATE stock_log stock_entry
SET order_id = (
    SELECT order_entry.id
    FROM shop_order order_entry
    WHERE order_entry.id = CAST(
        REGEXP_REPLACE(stock_entry.reason, '^.* ', '') AS DECIMAL(20, 0)
    )
      AND (
          stock_entry.reason = CONCAT('Order submit ', order_entry.id)
          OR stock_entry.reason LIKE CONCAT('% order ', order_entry.id)
      )
)
WHERE stock_entry.order_id IS NULL
  AND stock_entry.change_type IN ('ORDER_LOCK', 'ORDER_RELEASE')
  AND (
      stock_entry.reason REGEXP '^Order submit [0-9]{1,19}$'
      OR stock_entry.reason REGEXP '^.* order [0-9]{1,19}$'
  );

CREATE INDEX idx_stock_log_order
    ON stock_log(order_id, id);

CREATE TABLE order_cleanup_failure (
    source_order_id BIGINT PRIMARY KEY,
    consecutive_failures INT NOT NULL DEFAULT 1,
    next_retry_at TIMESTAMP NOT NULL,
    last_error VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_order_cleanup_failure_count CHECK (consecutive_failures > 0),
    CONSTRAINT fk_order_cleanup_failure_order FOREIGN KEY (source_order_id)
        REFERENCES shop_order(id) ON DELETE CASCADE
);

CREATE INDEX idx_order_cleanup_failure_retry
    ON order_cleanup_failure(next_retry_at, source_order_id);

CREATE TABLE order_archive_manifest (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_order_id BIGINT NOT NULL,
    order_no_digest CHAR(64) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    storage_container VARCHAR(500) NOT NULL,
    storage_region VARCHAR(64) NOT NULL,
    content_type VARCHAR(128) NOT NULL DEFAULT 'application/zip',
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    archive_format_version INT NOT NULL DEFAULT 1,
    item_count INT NOT NULL DEFAULT 0,
    payment_count INT NOT NULL DEFAULT 0,
    refund_count INT NOT NULL DEFAULT 0,
    after_sale_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PURGED',
    archived_at TIMESTAMP NOT NULL,
    purged_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_archive_source UNIQUE (source_order_id),
    CONSTRAINT uk_order_archive_object_key UNIQUE (object_key),
    CONSTRAINT chk_order_archive_provider CHECK (provider = 'TENCENT_COS'),
    CONSTRAINT chk_order_archive_size CHECK (size_bytes >= 0),
    CONSTRAINT chk_order_archive_counts CHECK (
        item_count >= 0
        AND payment_count >= 0
        AND refund_count >= 0
        AND after_sale_count >= 0
    ),
    CONSTRAINT chk_order_archive_status CHECK (status = 'PURGED')
);

CREATE INDEX idx_order_archive_purged
    ON order_archive_manifest(purged_at, id);

CREATE TABLE purged_order_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    archive_manifest_id BIGINT NOT NULL,
    user_idempotency_digest CHAR(64) NOT NULL,
    order_no_digest CHAR(64) NOT NULL,
    final_status VARCHAR(20) NOT NULL,
    purged_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_purged_order_idempotency UNIQUE (user_idempotency_digest),
    CONSTRAINT uk_purged_order_no UNIQUE (order_no_digest),
    CONSTRAINT uk_purged_order_archive UNIQUE (archive_manifest_id),
    CONSTRAINT fk_purged_order_archive FOREIGN KEY (archive_manifest_id)
        REFERENCES order_archive_manifest(id) ON DELETE RESTRICT
);

CREATE INDEX idx_purged_order_purged
    ON purged_order_identity(purged_at, id);

CREATE TABLE purged_payment_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    archive_manifest_id BIGINT NOT NULL,
    out_trade_no_digest CHAR(64) NOT NULL,
    transaction_id_digest CHAR(64) NOT NULL DEFAULT '',
    notification_route_digest CHAR(64) NULL,
    payment_config_id BIGINT NULL,
    payment_config_fingerprint CHAR(64) NOT NULL DEFAULT '',
    final_status VARCHAR(32) NOT NULL,
    amount_cent BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    purged_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_purged_payment_trade UNIQUE (out_trade_no_digest),
    CONSTRAINT uk_purged_payment_route UNIQUE (notification_route_digest),
    CONSTRAINT chk_purged_payment_amount CHECK (amount_cent > 0),
    CONSTRAINT fk_purged_payment_archive FOREIGN KEY (archive_manifest_id)
        REFERENCES order_archive_manifest(id) ON DELETE RESTRICT
);

CREATE INDEX idx_purged_payment_transaction
    ON purged_payment_identity(transaction_id_digest);

CREATE INDEX idx_purged_payment_config
    ON purged_payment_identity(payment_config_id);

CREATE INDEX idx_purged_payment_purged
    ON purged_payment_identity(purged_at, id);

CREATE TABLE purged_refund_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    archive_manifest_id BIGINT NOT NULL,
    out_refund_no_digest CHAR(64) NOT NULL,
    out_trade_no_digest CHAR(64) NOT NULL,
    refund_id_digest CHAR(64) NOT NULL DEFAULT '',
    notification_route_digest CHAR(64) NULL,
    payment_config_id BIGINT NULL,
    payment_config_fingerprint CHAR(64) NOT NULL DEFAULT '',
    final_status VARCHAR(32) NOT NULL,
    final_callback_status VARCHAR(32) NOT NULL,
    refund_amount_cent BIGINT NOT NULL,
    purged_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_purged_refund_no UNIQUE (out_refund_no_digest),
    CONSTRAINT uk_purged_refund_route UNIQUE (notification_route_digest),
    CONSTRAINT chk_purged_refund_amount CHECK (refund_amount_cent > 0),
    CONSTRAINT fk_purged_refund_archive FOREIGN KEY (archive_manifest_id)
        REFERENCES order_archive_manifest(id) ON DELETE RESTRICT
);

CREATE INDEX idx_purged_refund_trade
    ON purged_refund_identity(out_trade_no_digest);

CREATE INDEX idx_purged_refund_provider_id
    ON purged_refund_identity(refund_id_digest);

CREATE INDEX idx_purged_refund_config
    ON purged_refund_identity(payment_config_id);

CREATE INDEX idx_purged_refund_purged
    ON purged_refund_identity(purged_at, id);

CREATE INDEX idx_shop_order_cleanup_candidate
    ON shop_order(updated_at, id);

CREATE INDEX idx_refund_order_order
    ON refund_order(order_id, id);

CREATE INDEX idx_user_coupon_locked_order
    ON user_coupon(locked_order_id, id);

CREATE INDEX idx_user_coupon_used_order
    ON user_coupon(used_order_id, id);

CREATE INDEX idx_payment_callback_trade
    ON payment_callback_log(out_trade_no, updated_at, id);

CREATE INDEX idx_payment_callback_refund
    ON payment_callback_log(out_refund_no, updated_at, id);

CREATE INDEX idx_customer_service_message_order_resource
    ON customer_service_message(message_type, resource_id, conversation_id, id);

CREATE INDEX idx_customer_service_conversation_context
    ON customer_service_conversation(context_type, context_id, id);
