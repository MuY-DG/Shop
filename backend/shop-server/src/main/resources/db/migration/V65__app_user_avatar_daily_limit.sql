CREATE TABLE app_user_avatar_daily_limit (
    user_id BIGINT NOT NULL,
    limit_date DATE NOT NULL,
    change_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, limit_date)
);

CREATE INDEX idx_app_user_avatar_daily_limit_date
    ON app_user_avatar_daily_limit(limit_date, user_id);
