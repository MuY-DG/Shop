ALTER TABLE amap_runtime_setting
    RENAME COLUMN web_service_key_ciphertext TO mini_program_key_ciphertext;

UPDATE amap_runtime_setting
SET enabled = FALSE,
    mini_program_key_ciphertext = '',
    secret_revision = secret_revision + 1,
    updated_at = CURRENT_TIMESTAMP;
