-- Refuse inconsistent existing data. Run docs/database-v20-preflight.sql before deployment.
-- Zero-price product placeholders and unknown (NULL) historical costs remain supported.
ALTER TABLE product_sku
  ADD CONSTRAINT chk_product_sku_stock_nonnegative CHECK (stock_available >= 0);
ALTER TABLE product_sku
  ADD CONSTRAINT chk_product_sku_low_stock_nonnegative CHECK (low_stock_threshold >= 0);
ALTER TABLE product_sku
  ADD CONSTRAINT chk_product_sku_amounts_nonnegative CHECK (
    price_cent >= 0 AND original_price_cent >= 0
    AND (cost_price_cent IS NULL OR cost_price_cent >= 0)
  );

ALTER TABLE stock_lock
  ADD CONSTRAINT uk_stock_lock_order_item UNIQUE (order_item_id);
ALTER TABLE stock_lock
  ADD CONSTRAINT chk_stock_lock_quantity_positive CHECK (quantity > 0);

ALTER TABLE order_item
  ADD CONSTRAINT chk_order_item_quantity_positive CHECK (quantity > 0);
ALTER TABLE order_item
  ADD CONSTRAINT chk_order_item_amounts_nonnegative CHECK (
    original_price_cent >= 0 AND unit_price_cent >= 0
    AND retail_unit_price_cent >= 0
    AND line_original_amount_cent >= 0 AND line_amount_cent >= 0
    AND (unit_cost_cent IS NULL OR unit_cost_cent >= 0)
    AND (line_cost_cent IS NULL OR line_cost_cent >= 0)
    AND (coupon_discount_allocated_cent IS NULL OR coupon_discount_allocated_cent >= 0)
    AND (freight_allocated_cent IS NULL OR freight_allocated_cent >= 0)
    AND (paid_amount_allocated_cent IS NULL OR paid_amount_allocated_cent >= 0)
  );

ALTER TABLE shop_order
  ADD CONSTRAINT chk_shop_order_amounts_nonnegative CHECK (
    product_original_amount_cent >= 0 AND product_amount_cent >= 0
    AND coupon_discount_cent >= 0 AND freight_cent >= 0
    AND payable_amount_cent >= 0 AND paid_amount_cent >= 0
  );

ALTER TABLE stock_log
  ADD CONSTRAINT chk_stock_log_inventory_nonnegative CHECK (
    quantity_before >= 0 AND quantity_after >= 0
  );
ALTER TABLE stock_log
  ADD CONSTRAINT chk_stock_log_quantity_balance CHECK (
    quantity_after = CAST(quantity_before AS DECIMAL(20, 0)) + quantity_delta
  );
