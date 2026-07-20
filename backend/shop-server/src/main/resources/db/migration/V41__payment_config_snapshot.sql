CREATE TABLE payment_config_snapshot (
    fingerprint VARCHAR(64) PRIMARY KEY,
    config_source VARCHAR(16) NOT NULL,
    config_name VARCHAR(80) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    mch_id VARCHAR(32) NOT NULL,
    merchant_serial_no VARCHAR(128) NOT NULL,
    api_v3_key_ciphertext TEXT NOT NULL,
    private_key_pem_ciphertext TEXT NOT NULL,
    notify_url VARCHAR(255) NOT NULL,
    refund_notify_url VARCHAR(255) NOT NULL,
    verify_mode VARCHAR(32) NOT NULL,
    wechat_public_key_id VARCHAR(128) NOT NULL DEFAULT '',
    wechat_public_key_pem_ciphertext TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_order_config_identity_updated
    ON payment_order(payment_config_id, payment_config_fingerprint, updated_at);
