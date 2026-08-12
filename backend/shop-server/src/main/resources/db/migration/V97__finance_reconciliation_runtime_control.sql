CREATE TABLE finance_reconciliation_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    worker_enabled BOOLEAN NOT NULL,
    daily_enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_finance_reconciliation_runtime_singleton CHECK (id = 1),
    CONSTRAINT chk_finance_reconciliation_runtime_revision CHECK (revision >= 1),
    CONSTRAINT chk_finance_reconciliation_runtime_daily CHECK (
        daily_enabled = FALSE OR worker_enabled = TRUE
    )
);

CREATE TABLE finance_reconciliation_runtime_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision BIGINT NOT NULL,
    worker_enabled_before BOOLEAN NOT NULL,
    daily_enabled_before BOOLEAN NOT NULL,
    worker_enabled_after BOOLEAN NOT NULL,
    daily_enabled_after BOOLEAN NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    operator_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_reconciliation_runtime_audit_revision UNIQUE (revision),
    CONSTRAINT chk_finance_reconciliation_runtime_audit_revision CHECK (revision >= 1),
    CONSTRAINT chk_finance_reconciliation_runtime_audit_before CHECK (
        daily_enabled_before = FALSE OR worker_enabled_before = TRUE
    ),
    CONSTRAINT chk_finance_reconciliation_runtime_audit_after CHECK (
        daily_enabled_after = FALSE OR worker_enabled_after = TRUE
    )
);

CREATE INDEX idx_finance_reconciliation_runtime_audit_time
    ON finance_reconciliation_runtime_audit(created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (21006, 'finance:reconciliation:runtime:write', '修改财务对账运行开关');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 21006
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (921, 21006);
