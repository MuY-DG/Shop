CREATE TABLE data_cleanup_config (
    id BIGINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_data_cleanup_config_singleton CHECK (id = 1),
    CONSTRAINT chk_data_cleanup_config_revision CHECK (revision >= 0)
);

CREATE TABLE data_cleanup_task_setting (
    task_code VARCHAR(40) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    retention_days INT NULL,
    batch_size INT NOT NULL,
    cron_expression VARCHAR(80) NOT NULL,
    zone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    batch_interval_seconds INT NOT NULL DEFAULT 60,
    upload_pending_grace_minutes INT NULL,
    config_revision BIGINT NOT NULL DEFAULT 0,
    run_sequence BIGINT NOT NULL DEFAULT 0,
    next_run_at TIMESTAMP NULL,
    lease_token VARCHAR(36) NULL,
    lease_until TIMESTAMP NULL,
    last_started_at TIMESTAMP NULL,
    last_completed_at TIMESTAMP NULL,
    last_status VARCHAR(16) NOT NULL DEFAULT 'NEVER',
    last_processed_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_data_cleanup_task_code CHECK (
        task_code IN (
            'ANALYTICS_EVENT',
            'ADMIN_SYSTEM_LOG',
            'CUSTOMER_SERVICE_MESSAGE',
            'STORAGE_ASSET',
            'DIRECT_UPLOAD_SESSION'
        )
    ),
    CONSTRAINT chk_data_cleanup_retention_days CHECK (
        (task_code = 'ANALYTICS_EVENT'
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 367 AND 3650)
        OR (task_code IN ('ADMIN_SYSTEM_LOG', 'CUSTOMER_SERVICE_MESSAGE')
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 1 AND 3650)
        OR (task_code = 'DIRECT_UPLOAD_SESSION'
            AND retention_days IS NOT NULL
            AND retention_days BETWEEN 1 AND 365)
        OR (task_code = 'STORAGE_ASSET' AND retention_days IS NULL)
    ),
    CONSTRAINT chk_data_cleanup_batch_size CHECK (
        (task_code IN ('ANALYTICS_EVENT', 'ADMIN_SYSTEM_LOG')
            AND batch_size BETWEEN 1 AND 50000)
        OR (task_code = 'CUSTOMER_SERVICE_MESSAGE'
            AND batch_size BETWEEN 1 AND 10000)
        OR (task_code IN ('STORAGE_ASSET', 'DIRECT_UPLOAD_SESSION')
            AND batch_size BETWEEN 1 AND 1000)
    ),
    CONSTRAINT chk_data_cleanup_task_revision CHECK (config_revision >= 0),
    CONSTRAINT chk_data_cleanup_run_sequence CHECK (run_sequence >= 0),
    CONSTRAINT chk_data_cleanup_batch_interval CHECK (
        batch_interval_seconds BETWEEN 60 AND 86400
    ),
    CONSTRAINT chk_data_cleanup_upload_grace CHECK (
        (task_code = 'STORAGE_ASSET'
            AND upload_pending_grace_minutes IS NOT NULL
            AND upload_pending_grace_minutes BETWEEN 5 AND 10080)
        OR (task_code <> 'STORAGE_ASSET' AND upload_pending_grace_minutes IS NULL)
    ),
    CONSTRAINT chk_data_cleanup_last_status CHECK (
        last_status IN ('NEVER', 'RUNNING', 'SUCCESS', 'FAILED')
    ),
    CONSTRAINT chk_data_cleanup_last_count CHECK (last_processed_count >= 0)
);

CREATE INDEX idx_data_cleanup_task_due
    ON data_cleanup_task_setting(enabled, next_run_at, lease_until);

INSERT INTO data_cleanup_config (id, revision)
VALUES (1, 0);

INSERT INTO data_cleanup_task_setting (
    task_code,
    enabled,
    retention_days,
    batch_size,
    cron_expression,
    zone_id,
    batch_interval_seconds,
    upload_pending_grace_minutes
)
VALUES
    ('ANALYTICS_EVENT', TRUE, 400, 5000, '0 15 3 * * *', 'Asia/Shanghai', 60, NULL),
    ('ADMIN_SYSTEM_LOG', TRUE, 400, 5000, '0 45 3 * * *', 'Asia/Shanghai', 60, NULL),
    ('CUSTOMER_SERVICE_MESSAGE', FALSE, 365, 1000, '0 15 4 * * *', 'Asia/Shanghai', 60, NULL),
    ('STORAGE_ASSET', TRUE, NULL, 100, '0 */10 * * * *', 'Asia/Shanghai', 60, 30),
    ('DIRECT_UPLOAD_SESSION', TRUE, 7, 100, '0 */10 * * * *', 'Asia/Shanghai', 60, NULL);

CREATE INDEX idx_analytics_event_retention
    ON analytics_event(business_date, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (18005, 'data-cleanup:config:read', '查看数据清理配置'),
    (18006, 'data-cleanup:config:write', '修改数据清理配置');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    805, 800, 'DataCleanupConfig', 'data-cleanup',
    '/configuration/data-cleanup', '数据清理配置', 'ri:delete-bin-6-line',
    85, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_entry.id, permission_entry.id
FROM admin_role role_entry
JOIN admin_permission permission_entry
  ON permission_entry.id IN (18005, 18006)
WHERE role_entry.code = 'R_SUPER';

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_entry.id, 805
FROM admin_role role_entry
WHERE role_entry.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (805, 18005),
    (805, 18006);
