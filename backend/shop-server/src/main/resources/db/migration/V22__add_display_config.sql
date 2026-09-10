CREATE TABLE app_display_config (
    id INT NOT NULL PRIMARY KEY,
    display_name VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_by BIGINT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_app_display_config_singleton CHECK (id = 1),
    CONSTRAINT chk_app_display_config_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT chk_app_display_config_revision CHECK (revision > 0)
);

INSERT INTO app_display_config (id, display_name) VALUES (1, '蜀香序');
