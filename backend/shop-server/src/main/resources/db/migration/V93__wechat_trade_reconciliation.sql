CREATE TABLE finance_reconciliation_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    mch_id VARCHAR(32) NOT NULL,
    bill_date DATE NOT NULL,
    bill_type VARCHAR(24) NOT NULL DEFAULT 'TRADE_ALL',
    credential_config_id BIGINT NULL,
    credential_fingerprint CHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL,
    phase VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    provider_hash_verified BOOLEAN NOT NULL DEFAULT FALSE,
    content_sha256 CHAR(64) NOT NULL DEFAULT '',
    storage_provider VARCHAR(32) NOT NULL DEFAULT '',
    storage_container VARCHAR(128) NOT NULL DEFAULT '',
    storage_region VARCHAR(64) NOT NULL DEFAULT '',
    object_key VARCHAR(512) NOT NULL DEFAULT '',
    content_type VARCHAR(128) NOT NULL DEFAULT '',
    source_size_bytes BIGINT NOT NULL DEFAULT 0,
    total_rows BIGINT NOT NULL DEFAULT 0,
    payment_rows BIGINT NOT NULL DEFAULT 0,
    refund_rows BIGINT NOT NULL DEFAULT 0,
    channel_payment_amount_cent BIGINT NOT NULL DEFAULT 0,
    channel_refund_amount_cent BIGINT NOT NULL DEFAULT 0,
    local_payment_amount_cent BIGINT NOT NULL DEFAULT 0,
    local_refund_amount_cent BIGINT NOT NULL DEFAULT 0,
    difference_count BIGINT NOT NULL DEFAULT 0,
    open_difference_count BIGINT NOT NULL DEFAULT 0,
    claim_token VARCHAR(64) NULL,
    claimed_at TIMESTAMP NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    requested_by BIGINT NULL,
    requested_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_reconciliation_batch UNIQUE (mch_id, bill_date, bill_type),
    CONSTRAINT chk_finance_reconciliation_batch_bill_type CHECK (bill_type = 'TRADE_ALL'),
    CONSTRAINT chk_finance_reconciliation_batch_status CHECK (
        status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'BALANCED', 'DIFFERENCES', 'EMPTY', 'FAILED')
    ),
    CONSTRAINT chk_finance_reconciliation_batch_phase CHECK (
        phase IN ('QUEUED', 'DOWNLOAD', 'VERIFY', 'PARSE', 'STORE', 'COMPARE', 'COMPLETE')
    ),
    CONSTRAINT chk_finance_reconciliation_batch_counts CHECK (
        source_size_bytes >= 0 AND total_rows >= 0 AND payment_rows >= 0
        AND refund_rows >= 0 AND difference_count >= 0 AND open_difference_count >= 0
        AND attempt_count >= 0 AND total_rows = payment_rows + refund_rows
        AND open_difference_count <= difference_count
        AND channel_payment_amount_cent >= 0 AND channel_refund_amount_cent >= 0
        AND local_payment_amount_cent >= 0 AND local_refund_amount_cent >= 0
    ),
    CONSTRAINT chk_finance_reconciliation_batch_claim CHECK (
        (claim_token IS NULL AND claimed_at IS NULL)
        OR (claim_token IS NOT NULL AND claimed_at IS NOT NULL)
    )
);

CREATE INDEX idx_finance_reconciliation_batch_schedule
    ON finance_reconciliation_batch(status, next_attempt_at, claimed_at, id);

CREATE INDEX idx_finance_reconciliation_batch_date
    ON finance_reconciliation_batch(bill_date, mch_id, id);

CREATE TABLE wechat_trade_bill_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    row_no BIGINT NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    out_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    out_refund_no VARCHAR(64) NOT NULL DEFAULT '',
    occurred_at TIMESTAMP NOT NULL,
    amount_cent BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    channel_status VARCHAR(32) NOT NULL DEFAULT '',
    row_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wechat_trade_bill_entry_row UNIQUE (batch_id, row_no),
    CONSTRAINT fk_wechat_trade_bill_entry_batch FOREIGN KEY (batch_id)
        REFERENCES finance_reconciliation_batch(id) ON DELETE RESTRICT,
    CONSTRAINT chk_wechat_trade_bill_entry_type CHECK (entry_type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT chk_wechat_trade_bill_entry_amount CHECK (amount_cent >= 0),
    CONSTRAINT chk_wechat_trade_bill_entry_row_no CHECK (row_no > 0)
);

CREATE INDEX idx_wechat_trade_bill_entry_payment
    ON wechat_trade_bill_entry(batch_id, out_trade_no, transaction_id, id);

CREATE INDEX idx_wechat_trade_bill_entry_refund
    ON wechat_trade_bill_entry(batch_id, out_refund_no, refund_id, id);

CREATE INDEX idx_wechat_trade_bill_entry_digest
    ON wechat_trade_bill_entry(batch_id, row_digest, id);

CREATE TABLE finance_reconciliation_difference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    diff_key CHAR(64) NOT NULL,
    difference_type VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    out_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    out_refund_no VARCHAR(64) NOT NULL DEFAULT '',
    order_id BIGINT NULL,
    payment_order_id BIGINT NULL,
    refund_order_id BIGINT NULL,
    provider_amount_cent BIGINT NULL,
    local_amount_cent BIGINT NULL,
    provider_status VARCHAR(32) NOT NULL DEFAULT '',
    local_status VARCHAR(32) NOT NULL DEFAULT '',
    provider_evidence TEXT NOT NULL,
    local_evidence TEXT NOT NULL,
    candidate_content_sha256 CHAR(64) NOT NULL DEFAULT '',
    candidate_storage_provider VARCHAR(32) NOT NULL DEFAULT '',
    candidate_storage_container VARCHAR(128) NOT NULL DEFAULT '',
    candidate_storage_region VARCHAR(64) NOT NULL DEFAULT '',
    candidate_object_key VARCHAR(512) NOT NULL DEFAULT '',
    candidate_size_bytes BIGINT NULL,
    resolution_code VARCHAR(64) NOT NULL DEFAULT '',
    resolution_reason VARCHAR(500) NOT NULL DEFAULT '',
    resolved_by BIGINT NULL,
    resolved_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_reconciliation_difference UNIQUE (batch_id, diff_key),
    CONSTRAINT fk_finance_reconciliation_difference_batch FOREIGN KEY (batch_id)
        REFERENCES finance_reconciliation_batch(id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_reconciliation_difference_order FOREIGN KEY (order_id)
        REFERENCES shop_order(id) ON DELETE SET NULL,
    CONSTRAINT fk_finance_reconciliation_difference_payment FOREIGN KEY (payment_order_id)
        REFERENCES payment_order(id) ON DELETE SET NULL,
    CONSTRAINT fk_finance_reconciliation_difference_refund FOREIGN KEY (refund_order_id)
        REFERENCES refund_order(id) ON DELETE SET NULL,
    CONSTRAINT chk_finance_reconciliation_difference_type CHECK (
        difference_type IN (
            'CHANNEL_ONLY', 'LOCAL_ONLY', 'AMOUNT_MISMATCH', 'IDENTITY_MISMATCH',
            'STATUS_MISMATCH', 'DUPLICATE_CHANNEL_ROW', 'SOURCE_CHANGED'
        )
    ),
    CONSTRAINT chk_finance_reconciliation_difference_severity CHECK (
        severity IN ('INFO', 'WARNING', 'CRITICAL')
    ),
    CONSTRAINT chk_finance_reconciliation_difference_status CHECK (
        status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'AUTO_CLEARED')
    ),
    CONSTRAINT chk_finance_reconciliation_difference_candidate CHECK (
        (
            candidate_content_sha256 = '' AND candidate_storage_provider = ''
            AND candidate_storage_container = '' AND candidate_storage_region = ''
            AND candidate_object_key = '' AND candidate_size_bytes IS NULL
        ) OR (
            difference_type = 'SOURCE_CHANGED'
            AND CHAR_LENGTH(candidate_content_sha256) = 64
            AND candidate_storage_provider <> '' AND candidate_storage_container <> ''
            AND candidate_object_key <> '' AND candidate_size_bytes IS NOT NULL
            AND candidate_size_bytes >= 0
        )
    )
);

CREATE INDEX idx_finance_reconciliation_difference_batch_status
    ON finance_reconciliation_difference(batch_id, status, difference_type, id);

CREATE INDEX idx_finance_reconciliation_difference_payment_status
    ON finance_reconciliation_difference(payment_order_id, status, id);

CREATE INDEX idx_finance_reconciliation_difference_refund_status
    ON finance_reconciliation_difference(refund_order_id, status, id);

CREATE INDEX idx_finance_reconciliation_difference_order_status
    ON finance_reconciliation_difference(order_id, status, id);

CREATE INDEX idx_refund_order_reconciliation_requested
    ON refund_order(requested_at, payment_order_id, id);

CREATE INDEX idx_refund_order_reconciliation_payment
    ON refund_order(payment_order_id, id);

CREATE INDEX idx_refund_order_reconciliation_refund_id
    ON refund_order(refund_id, id);

CREATE TABLE finance_reconciliation_resolution_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NULL,
    difference_id BIGINT NULL,
    from_status VARCHAR(24) NOT NULL,
    to_status VARCHAR(24) NOT NULL,
    action VARCHAR(32) NOT NULL,
    resolution_code VARCHAR(64) NOT NULL DEFAULT '',
    reason VARCHAR(500) NOT NULL,
    metadata VARCHAR(1000) NOT NULL DEFAULT '',
    operator_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_finance_reconciliation_resolution_batch FOREIGN KEY (batch_id)
        REFERENCES finance_reconciliation_batch(id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_reconciliation_resolution_difference FOREIGN KEY (difference_id)
        REFERENCES finance_reconciliation_difference(id) ON DELETE RESTRICT,
    CONSTRAINT chk_finance_reconciliation_resolution_action CHECK (
        action IN (
            'RUN', 'RETRY', 'SOURCE_DOWNLOAD', 'EXPORT',
            'INVESTIGATE', 'RESOLVE', 'AUTO_CLEAR', 'REOPEN'
        )
    )
);

CREATE INDEX idx_finance_reconciliation_resolution_time
    ON finance_reconciliation_resolution_audit(difference_id, created_at, id);

CREATE INDEX idx_finance_reconciliation_batch_audit_time
    ON finance_reconciliation_resolution_audit(batch_id, created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (21001, 'finance:reconciliation:read', '查看财务对账'),
    (21002, 'finance:reconciliation:run', '运行财务对账'),
    (21003, 'finance:reconciliation:resolve', '处理财务差异'),
    (21004, 'finance:reconciliation:source-download', '下载原始交易账单'),
    (21005, 'finance:export', '导出财务对账数据');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES
    (920, NULL, 'Finance', '/finance', '/index/index', '财务管理',
     'ri:money-cny-circle-line', 920, FALSE, TRUE, TRUE),
    (921, 920, 'FinanceReconciliation', 'reconciliation', '/finance/reconciliation/index', '财务对账',
     'ri:file-list-3-line', 921, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, menu_item.id
FROM admin_role role_item
CROSS JOIN admin_menu menu_item
WHERE role_item.code = 'R_SUPER'
  AND menu_item.id IN (920, 921);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (21001, 21002, 21003, 21004, 21005);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (921, 21001),
    (921, 21002),
    (921, 21003),
    (921, 21004),
    (921, 21005);
