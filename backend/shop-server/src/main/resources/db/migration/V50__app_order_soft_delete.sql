ALTER TABLE shop_order
    ADD COLUMN app_deleted_at TIMESTAMP NULL;

CREATE INDEX idx_shop_order_user_app_visible_created
    ON shop_order(user_id, app_deleted_at, created_at, id);
