CREATE TABLE shipment_tracking_snapshot (
    shipment_id BIGINT PRIMARY KEY,
    query_supported BOOLEAN NOT NULL DEFAULT FALSE,
    query_sync_status VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUESTED',
    logistics_status INT NULL,
    query_error_code VARCHAR(64) NOT NULL DEFAULT '',
    query_error_message VARCHAR(255) NOT NULL DEFAULT '',
    path_supported BOOLEAN NOT NULL DEFAULT FALSE,
    path_sync_status VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUESTED',
    path_error_code VARCHAR(64) NOT NULL DEFAULT '',
    path_error_message VARCHAR(255) NOT NULL DEFAULT '',
    claim_token VARCHAR(36) NULL,
    claimed_at TIMESTAMP NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP NULL,
    last_synced_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_shipment_tracking_query_status CHECK (
        query_sync_status IN (
            'NOT_REQUESTED', 'SYNCING', 'SYNCED', 'UNSUPPORTED',
            'FAILED', 'UNKNOWN', 'UNAVAILABLE'
        )
    ),
    CONSTRAINT chk_shipment_tracking_path_status CHECK (
        path_sync_status IN (
            'NOT_REQUESTED', 'SYNCING', 'SYNCED', 'UNSUPPORTED',
            'FAILED', 'UNKNOWN', 'UNAVAILABLE'
        )
    ),
    CONSTRAINT chk_shipment_tracking_logistics_status CHECK (
        logistics_status IS NULL OR logistics_status BETWEEN 0 AND 6
    ),
    CONSTRAINT chk_shipment_tracking_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_shipment_tracking_claim
    ON shipment_tracking_snapshot(claimed_at, shipment_id);

CREATE TABLE shipment_tracking_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    action_time BIGINT NOT NULL,
    action_type INT NOT NULL,
    action_message VARCHAR(512) NOT NULL,
    message_digest CHAR(64) NOT NULL,
    display_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_shipment_tracking_event_time CHECK (action_time > 0),
    CONSTRAINT chk_shipment_tracking_event_order CHECK (display_order >= 0)
);

CREATE UNIQUE INDEX uk_shipment_tracking_event_identity
    ON shipment_tracking_event(shipment_id, action_time, action_type, message_digest);

CREATE INDEX idx_shipment_tracking_event_display
    ON shipment_tracking_event(shipment_id, display_order, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (8401, 'order:shipping:tracking:sync', '同步订单物流轨迹');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, 8401
FROM admin_role role_item
WHERE role_item.code = 'R_SUPER';

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (501, 8401);
