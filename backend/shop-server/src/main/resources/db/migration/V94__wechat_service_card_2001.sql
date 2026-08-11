CREATE TABLE wechat_service_card (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_order_id BIGINT NOT NULL,
    notify_type INT NOT NULL DEFAULT 2001,
    notify_code_digest CHAR(64) NOT NULL,
    account_template_record_id VARCHAR(128) NOT NULL DEFAULT '',
    last_enqueued_status SMALLINT NULL,
    restore_status SMALLINT NULL,
    remote_status SMALLINT NULL,
    remote_code_state SMALLINT NULL,
    remote_code_expire_at TIMESTAMP NULL,
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    send_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    send_block_reason VARCHAR(32) NOT NULL DEFAULT '',
    send_blocked_at TIMESTAMP NULL,
    activated_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_wechat_service_card_order
    ON wechat_service_card(order_id);

CREATE UNIQUE INDEX uk_wechat_service_card_payment
    ON wechat_service_card(payment_order_id);

CREATE INDEX idx_wechat_service_card_notify_digest
    ON wechat_service_card(notify_code_digest, id);

ALTER TABLE wechat_service_card
    ADD CONSTRAINT chk_wechat_service_card_notify_type CHECK (notify_type = 2001);

ALTER TABLE wechat_service_card
    ADD CONSTRAINT chk_wechat_service_card_statuses CHECK (
        (last_enqueued_status IS NULL OR last_enqueued_status BETWEEN 1 AND 11)
        AND (restore_status IS NULL OR restore_status IN (2, 4, 6))
        AND (remote_status IS NULL OR remote_status BETWEEN 1 AND 11)
        AND (remote_code_state IS NULL OR remote_code_state IN (0, 1, 2, 10))
        AND (
            (send_blocked = FALSE AND send_block_reason = '' AND send_blocked_at IS NULL)
            OR
            (send_blocked = TRUE AND send_block_reason = 'USER_REFUSED'
                AND send_blocked_at IS NOT NULL)
        )
    );

ALTER TABLE wechat_service_card
    ADD CONSTRAINT fk_wechat_service_card_order
    FOREIGN KEY (order_id) REFERENCES shop_order(id);

ALTER TABLE wechat_service_card
    ADD CONSTRAINT fk_wechat_service_card_payment
    FOREIGN KEY (payment_order_id) REFERENCES payment_order(id);

CREATE TABLE wechat_service_card_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    card_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    target_status SMALLINT NOT NULL,
    content_json TEXT NOT NULL,
    check_json TEXT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    reconcile_attempt_count INT NOT NULL DEFAULT 0,
    claim_token VARCHAR(36) NULL,
    claimed_at TIMESTAMP NULL,
    next_action_at TIMESTAMP NULL,
    not_applied_observations INT NOT NULL DEFAULT 0,
    last_reconciled_at TIMESTAMP NULL,
    provider_error_code VARCHAR(64) NOT NULL DEFAULT '',
    provider_error_message VARCHAR(255) NOT NULL DEFAULT '',
    applied_at TIMESTAMP NULL,
    message_result_state VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    message_fail_ret INT NULL,
    message_fail_message VARCHAR(255) NOT NULL DEFAULT '',
    message_result_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_wechat_service_card_delivery_sequence
    ON wechat_service_card_delivery(card_id, sequence_no);

CREATE UNIQUE INDEX uk_wechat_service_card_delivery_id_card
    ON wechat_service_card_delivery(id, card_id);

CREATE INDEX idx_wechat_service_card_delivery_due
    ON wechat_service_card_delivery(state, next_action_at, id);

CREATE INDEX idx_wechat_service_card_delivery_card_state
    ON wechat_service_card_delivery(card_id, state, sequence_no);

ALTER TABLE wechat_service_card_delivery
    ADD CONSTRAINT chk_wechat_service_card_delivery_sequence CHECK (sequence_no > 0);

ALTER TABLE wechat_service_card_delivery
    ADD CONSTRAINT chk_wechat_service_card_delivery_attempts CHECK (
        attempt_count >= 0 AND reconcile_attempt_count >= 0 AND not_applied_observations >= 0
    );

ALTER TABLE wechat_service_card_delivery
    ADD CONSTRAINT chk_wechat_service_card_delivery_state CHECK (
        state IN ('PENDING', 'SENDING', 'UNKNOWN', 'RECONCILING', 'SUCCEEDED', 'FAILED', 'SKIPPED')
        AND target_status BETWEEN 1 AND 11
        AND message_result_state IN ('UNKNOWN', 'FAILED')
        AND (
            (message_result_state = 'UNKNOWN'
                AND message_fail_ret IS NULL
                AND message_result_at IS NULL)
            OR
            (message_result_state = 'FAILED'
                AND message_fail_ret < 0
                AND message_result_at IS NOT NULL)
        )
    );

ALTER TABLE wechat_service_card_delivery
    ADD CONSTRAINT chk_wechat_service_card_delivery_claim CHECK (
        (
            state IN ('SENDING', 'RECONCILING')
            AND claim_token IS NOT NULL
            AND claimed_at IS NOT NULL
        ) OR (
            state NOT IN ('SENDING', 'RECONCILING')
            AND claim_token IS NULL
            AND claimed_at IS NULL
        )
    );

ALTER TABLE wechat_service_card_delivery
    ADD CONSTRAINT fk_wechat_service_card_delivery_card
    FOREIGN KEY (card_id) REFERENCES wechat_service_card(id);

CREATE TABLE wechat_service_card_callback_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_digest CHAR(64) NOT NULL,
    card_id BIGINT NULL,
    delivery_id BIGINT NULL,
    card_status SMALLINT NULL,
    fail_ret INT NULL,
    fail_message VARCHAR(255) NOT NULL DEFAULT '',
    matched BOOLEAN NOT NULL DEFAULT FALSE,
    received_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_wechat_service_card_callback_digest
    ON wechat_service_card_callback_log(event_digest);

CREATE INDEX idx_wechat_service_card_callback_received
    ON wechat_service_card_callback_log(received_at, id);

ALTER TABLE wechat_service_card_callback_log
    ADD CONSTRAINT chk_wechat_service_card_callback_status CHECK (
        (card_status IS NULL OR card_status BETWEEN 1 AND 11)
        AND (fail_ret IS NULL OR fail_ret < 0)
        AND (delivery_id IS NULL OR matched = TRUE)
        AND (matched = FALSE OR (card_id IS NOT NULL AND delivery_id IS NOT NULL))
    );

ALTER TABLE wechat_service_card_callback_log
    ADD CONSTRAINT fk_wechat_service_card_callback_card
    FOREIGN KEY (card_id) REFERENCES wechat_service_card(id);

ALTER TABLE wechat_service_card_callback_log
    ADD CONSTRAINT fk_wechat_service_card_callback_delivery_card
    FOREIGN KEY (delivery_id, card_id)
    REFERENCES wechat_service_card_delivery(id, card_id);
