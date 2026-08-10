ALTER TABLE app_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE app_user
    ADD COLUMN cancelled_at TIMESTAMP NULL;

CREATE TABLE app_user_rights_request (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_request_key SMALLINT NULL,
    request_note VARCHAR(1000) NOT NULL DEFAULT '',
    identity_verified_at TIMESTAMP NULL,
    review_reason VARCHAR(500) NOT NULL DEFAULT '',
    retention_explanation VARCHAR(1000) NOT NULL DEFAULT '',
    retained_data_categories VARCHAR(1000) NOT NULL DEFAULT '',
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    approved_at TIMESTAMP NULL,
    rejected_at TIMESTAMP NULL,
    withdrawn_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_app_user_rights_request_user FOREIGN KEY (user_id)
        REFERENCES app_user(id),
    CONSTRAINT chk_app_user_rights_request_type CHECK (
        request_type IN (
            'ACCOUNT_CANCELLATION',
            'PERSONAL_INFORMATION_DELETION',
            'ACCESS_COPY',
            'CORRECTION'
        )
    ),
    CONSTRAINT chk_app_user_rights_request_status CHECK (
        status IN (
            'PENDING',
            'IN_REVIEW',
            'APPROVED',
            'REJECTED',
            'WITHDRAWN',
            'COMPLETED'
        )
    ),
    CONSTRAINT chk_app_user_rights_request_active_key CHECK (
        (
            status IN ('PENDING', 'IN_REVIEW', 'APPROVED')
            AND active_request_key = 1
        )
        OR (
            status IN ('REJECTED', 'WITHDRAWN', 'COMPLETED')
            AND active_request_key IS NULL
        )
    ),
    CONSTRAINT chk_app_user_rights_identity_verification CHECK (
        request_type <> 'ACCOUNT_CANCELLATION'
        OR identity_verified_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX uk_app_user_rights_active_request
    ON app_user_rights_request(user_id, active_request_key);

CREATE INDEX idx_app_user_rights_request_status_time
    ON app_user_rights_request(status, created_at, id);

CREATE INDEX idx_app_user_rights_request_user_time
    ON app_user_rights_request(user_id, created_at, id);

CREATE TABLE app_user_rights_request_audit (
    id BIGINT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BIGINT NULL,
    from_status VARCHAR(20) NOT NULL DEFAULT '',
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    retention_explanation VARCHAR(1000) NOT NULL DEFAULT '',
    retained_data_categories VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_app_user_rights_audit_request FOREIGN KEY (request_id)
        REFERENCES app_user_rights_request(id),
    CONSTRAINT chk_app_user_rights_audit_action CHECK (
        action IN ('SUBMITTED', 'WITHDRAWN', 'REVIEWED', 'APPROVED', 'REJECTED', 'COMPLETED')
    ),
    CONSTRAINT chk_app_user_rights_audit_actor CHECK (
        actor_type IN ('APP_USER', 'ADMIN')
    )
);

CREATE INDEX idx_app_user_rights_audit_request_time
    ON app_user_rights_request_audit(request_id, created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (20001, 'account-rights:read', '查看账户权利申请'),
    (20002, 'account-rights:manage', '处理账户权利申请');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES (
    910, NULL, 'AccountRights', '/account-rights', '/account-rights/index',
    '账户权利申请', 'ri:user-settings-line', 910, TRUE, TRUE, TRUE
);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, 910
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (20001, 20002);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (910, 20001),
    (910, 20002);
