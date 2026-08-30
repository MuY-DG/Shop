ALTER TABLE after_sale_request
  ADD COLUMN app_deleted_at timestamp NULL DEFAULT NULL AFTER cancelled_at;

CREATE INDEX idx_after_sale_app_visible_created
  ON after_sale_request (app_deleted_at, created_at, id);
