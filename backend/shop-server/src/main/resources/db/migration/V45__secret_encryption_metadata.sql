ALTER TABLE payment_config
    ADD COLUMN secret_cipher_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE payment_config
    ADD COLUMN secret_key_id VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE payment_config
    ADD COLUMN secret_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_config
    ADD COLUMN secret_reencrypted_at TIMESTAMP NULL;

CREATE INDEX idx_payment_config_secret_key
    ON payment_config(secret_cipher_version, secret_key_id, id);

ALTER TABLE payment_config_snapshot
    ADD COLUMN secret_cipher_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE payment_config_snapshot
    ADD COLUMN secret_key_id VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE payment_config_snapshot
    ADD COLUMN secret_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_config_snapshot
    ADD COLUMN secret_reencrypted_at TIMESTAMP NULL;

CREATE INDEX idx_payment_config_snapshot_secret_key
    ON payment_config_snapshot(secret_cipher_version, secret_key_id, created_at);

ALTER TABLE storage_runtime_setting
    ADD COLUMN secret_cipher_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE storage_runtime_setting
    ADD COLUMN secret_key_id VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE storage_runtime_setting
    ADD COLUMN secret_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE storage_runtime_setting
    ADD COLUMN secret_reencrypted_at TIMESTAMP NULL;

CREATE TABLE payment_secret_rotation_checkpoint (
    checkpoint_name VARCHAR(64) NOT NULL PRIMARY KEY,
    cursor_value VARCHAR(128) NOT NULL DEFAULT '',
    scan_epoch BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO payment_secret_rotation_checkpoint
    (checkpoint_name, cursor_value, scan_epoch)
VALUES
    ('payment-config', '0', 0),
    ('payment-config-snapshot', '', 0),
    ('storage-runtime-setting', '0', 0);
