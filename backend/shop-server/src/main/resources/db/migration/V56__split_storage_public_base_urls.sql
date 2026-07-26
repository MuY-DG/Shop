ALTER TABLE storage_runtime_setting
    ADD COLUMN local_public_base_url VARCHAR(500) NOT NULL DEFAULT '';

ALTER TABLE storage_runtime_setting
    ADD COLUMN cos_public_base_url VARCHAR(500) NOT NULL DEFAULT '';

UPDATE storage_runtime_setting
SET local_public_base_url = CASE
        WHEN provider = 'LOCAL' THEN public_base_url
        ELSE ''
    END,
    cos_public_base_url = CASE
        WHEN provider = 'TENCENT_COS' THEN public_base_url
        ELSE ''
    END;
