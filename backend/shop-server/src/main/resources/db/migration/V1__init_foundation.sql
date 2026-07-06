CREATE TABLE system_health_marker (
    id BIGINT PRIMARY KEY,
    marker_key VARCHAR(64) NOT NULL,
    marker_value VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO system_health_marker (id, marker_key, marker_value)
VALUES (1, 'schema', 'foundation');
