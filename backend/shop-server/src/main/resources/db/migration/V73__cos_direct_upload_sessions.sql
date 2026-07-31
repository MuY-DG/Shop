ALTER TABLE storage_asset
    ADD COLUMN object_etag VARCHAR(128) NULL;

ALTER TABLE storage_asset
    ADD COLUMN thumbnail_object_etag VARCHAR(128) NULL;

CREATE TABLE storage_upload_principal_guard (
    principal_kind VARCHAR(16) NOT NULL,
    principal_id BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (principal_kind, principal_id),
    CONSTRAINT chk_storage_upload_guard_principal_kind CHECK (
        principal_kind IN ('ADMIN', 'APP')
    )
);

CREATE TABLE storage_upload_session (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    profile VARCHAR(40) NOT NULL,
    principal_kind VARCHAR(16) NOT NULL,
    principal_id BIGINT NOT NULL,
    folder_id BIGINT NULL,
    upload_context_type VARCHAR(40) NULL,
    upload_context_id BIGINT NULL,
    original_filename VARCHAR(255) NOT NULL,
    source_content_type VARCHAR(128) NOT NULL,
    expected_size_bytes BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    storage_container VARCHAR(500) NOT NULL,
    storage_region VARCHAR(64) NOT NULL,
    public_base_url VARCHAR(500) NOT NULL DEFAULT '',
    staging_object_key VARCHAR(255) NOT NULL,
    final_object_key VARCHAR(255) NOT NULL,
    thumbnail_object_key VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    asset_id BIGINT NULL,
    business_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    business_result_id BIGINT NULL,
    expires_at TIMESTAMP NOT NULL,
    processing_started_at TIMESTAMP NULL,
    processing_token VARCHAR(36) NULL,
    processing_attempts INT NOT NULL DEFAULT 0,
    next_processing_attempt_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    failure_code VARCHAR(64) NULL,
    staging_deleted_at TIMESTAMP NULL,
    outputs_deleted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_storage_upload_session_staging_key UNIQUE (staging_object_key),
    CONSTRAINT uk_storage_upload_session_final_key UNIQUE (final_object_key),
    CONSTRAINT chk_storage_upload_session_status CHECK (
        status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT chk_storage_upload_session_business_status CHECK (
        business_status IN ('NONE', 'COMPLETED')
    ),
    CONSTRAINT chk_storage_upload_session_principal_kind CHECK (
        principal_kind IN ('ADMIN', 'APP')
    ),
    CONSTRAINT chk_storage_upload_session_provider CHECK (
        provider = 'TENCENT_COS'
    ),
    CONSTRAINT chk_storage_upload_session_size CHECK (expected_size_bytes > 0),
    CONSTRAINT chk_storage_upload_session_processing_attempts CHECK (
        processing_attempts >= 0 AND processing_attempts <= 3
    ),
    CONSTRAINT chk_storage_upload_session_context CHECK (
        (upload_context_type IS NULL AND upload_context_id IS NULL)
        OR (upload_context_type IS NOT NULL AND upload_context_id IS NOT NULL)
    ),
    CONSTRAINT fk_storage_upload_session_asset FOREIGN KEY (asset_id)
        REFERENCES storage_asset (id) ON DELETE SET NULL,
    CONSTRAINT fk_storage_upload_session_folder FOREIGN KEY (folder_id)
        REFERENCES storage_asset_folder (id) ON DELETE SET NULL,
    INDEX idx_storage_upload_session_owner (
        principal_kind, principal_id, status, created_at
    ),
    INDEX idx_storage_upload_session_expiry (status, expires_at)
);

CREATE INDEX idx_storage_upload_session_retention
    ON storage_upload_session(status, updated_at);

DELETE FROM admin_menu_permission
WHERE menu_id = 804;

DELETE FROM admin_role_menu
WHERE menu_id = 804;

DELETE FROM admin_menu
WHERE id = 804;

DELETE FROM admin_role_permission
WHERE permission_id IN (18003, 18004);

DELETE FROM admin_permission
WHERE id IN (18003, 18004);
