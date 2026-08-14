CREATE TABLE wechat_service_card_config (
    id BIGINT NOT NULL PRIMARY KEY,
    account_template_record_id VARCHAR(128) NOT NULL,
    fallback_product_image VARCHAR(2048) NOT NULL,
    allowed_image_hosts VARCHAR(2048) NOT NULL,
    prefer_order_snapshot_images BOOLEAN NOT NULL,
    callback_enabled BOOLEAN NOT NULL,
    callback_token_ciphertext VARCHAR(1000) NULL,
    callback_token_cipher_version INT NULL,
    callback_token_key_id VARCHAR(64) NULL,
    callback_token_secret_revision BIGINT NOT NULL DEFAULT 0,
    callback_aes_key_ciphertext VARCHAR(1000) NULL,
    callback_aes_key_cipher_version INT NULL,
    callback_aes_key_key_id VARCHAR(64) NULL,
    callback_aes_key_secret_revision BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL,
    imported_from_env_at TIMESTAMP NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    callback_token_reencrypted_at TIMESTAMP NULL,
    callback_aes_key_reencrypted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wechat_service_card_config_singleton CHECK (id = 1),
    CONSTRAINT chk_wechat_service_card_config_template CHECK (account_template_record_id <> ''),
    CONSTRAINT chk_wechat_service_card_config_image CHECK (fallback_product_image <> ''),
    CONSTRAINT chk_wechat_service_card_config_hosts CHECK (allowed_image_hosts <> ''),
    CONSTRAINT chk_wechat_service_card_config_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_service_card_config_token_revision CHECK (
        callback_token_secret_revision >= 0
    ),
    CONSTRAINT chk_wechat_service_card_config_aes_revision CHECK (
        callback_aes_key_secret_revision >= 0
    ),
    CONSTRAINT chk_wechat_service_card_config_token_envelope CHECK (
        (callback_token_ciphertext IS NULL
            AND callback_token_cipher_version IS NULL
            AND callback_token_key_id IS NULL
            AND callback_token_secret_revision = 0)
        OR
        (callback_token_ciphertext IS NOT NULL
            AND callback_token_cipher_version IN (1, 2)
            AND callback_token_secret_revision >= 1
            AND (
                (callback_token_cipher_version = 1 AND callback_token_key_id = '')
                OR (callback_token_cipher_version = 2 AND callback_token_key_id <> '')
            ))
    ),
    CONSTRAINT chk_wechat_service_card_config_aes_envelope CHECK (
        (callback_aes_key_ciphertext IS NULL
            AND callback_aes_key_cipher_version IS NULL
            AND callback_aes_key_key_id IS NULL
            AND callback_aes_key_secret_revision = 0)
        OR
        (callback_aes_key_ciphertext IS NOT NULL
            AND callback_aes_key_cipher_version IN (1, 2)
            AND callback_aes_key_secret_revision >= 1
            AND (
                (callback_aes_key_cipher_version = 1 AND callback_aes_key_key_id = '')
                OR (callback_aes_key_cipher_version = 2 AND callback_aes_key_key_id <> '')
            ))
    ),
    CONSTRAINT chk_wechat_service_card_config_callback CHECK (
        callback_enabled = FALSE
        OR (callback_token_ciphertext IS NOT NULL AND callback_aes_key_ciphertext IS NOT NULL)
    )
);

CREATE TABLE wechat_service_card_config_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision BIGINT NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    template_record_id_before VARCHAR(128) NOT NULL,
    template_record_id_after VARCHAR(128) NOT NULL,
    fallback_image_before VARCHAR(2048) NOT NULL,
    fallback_image_after VARCHAR(2048) NOT NULL,
    allowed_hosts_before VARCHAR(2048) NOT NULL,
    allowed_hosts_after VARCHAR(2048) NOT NULL,
    prefer_snapshot_before BOOLEAN NOT NULL,
    prefer_snapshot_after BOOLEAN NOT NULL,
    callback_enabled_before BOOLEAN NOT NULL,
    callback_enabled_after BOOLEAN NOT NULL,
    callback_token_configured_before BOOLEAN NOT NULL,
    callback_token_configured_after BOOLEAN NOT NULL,
    callback_aes_key_configured_before BOOLEAN NOT NULL,
    callback_aes_key_configured_after BOOLEAN NOT NULL,
    operator_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wechat_service_card_config_audit_revision UNIQUE (revision),
    CONSTRAINT chk_wechat_service_card_config_audit_revision CHECK (revision >= 1),
    CONSTRAINT chk_wechat_service_card_config_audit_action CHECK (
        action_type IN ('CREATE', 'UPDATE', 'LEGACY_IMPORT')
    )
);

CREATE INDEX idx_wechat_service_card_config_audit_time
    ON wechat_service_card_config_audit(created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (23003, 'wechat-service-card:config:read', '查看微信服务动态接入配置'),
    (23004, 'wechat-service-card:config:write', '修改微信服务动态接入配置');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (23003, 23004);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, 806
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER'
  AND NOT EXISTS (
      SELECT 1
      FROM admin_role_menu existing
      WHERE existing.role_id = role_item.id
        AND existing.menu_id = 806
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (806, 23003),
    (806, 23004);
