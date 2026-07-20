ALTER TABLE payment_order
    ADD COLUMN payment_config_fingerprint VARCHAR(64) NOT NULL DEFAULT '';
