ALTER TABLE storage_asset
    ADD CONSTRAINT chk_storage_asset_cos_only CHECK (provider = 'TENCENT_COS');

ALTER TABLE storage_runtime_setting
    DROP CONSTRAINT chk_storage_runtime_setting_provider;

ALTER TABLE storage_runtime_setting
    DROP COLUMN provider;

ALTER TABLE storage_runtime_setting
    DROP COLUMN public_base_url;

ALTER TABLE storage_runtime_setting
    DROP COLUMN local_public_base_url;

ALTER TABLE storage_runtime_setting
    DROP COLUMN local_root;
