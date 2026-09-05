-- V20 库存与金额约束：只读预检，适用于 V18 / V19 的 MySQL 数据库。
-- 先连接实际业务库，再执行本文件。第一组结果的 violation_count 必须全部为 0。
-- 后续结果只显示问题记录的业务 ID 和数值，不修改、删除或自动去重任何记录。

SELECT 'product_sku.stock_available' AS check_name, COUNT(*) AS violation_count
FROM product_sku WHERE stock_available < 0
UNION ALL
SELECT 'product_sku.low_stock_threshold', COUNT(*)
FROM product_sku WHERE low_stock_threshold < 0
UNION ALL
SELECT 'product_sku.amounts', COUNT(*)
FROM product_sku WHERE price_cent < 0 OR original_price_cent < 0 OR cost_price_cent < 0
UNION ALL
SELECT 'stock_lock.duplicate_order_item', COUNT(*)
FROM (SELECT order_item_id FROM stock_lock GROUP BY order_item_id HAVING COUNT(*) > 1) duplicates
UNION ALL
SELECT 'stock_lock.quantity', COUNT(*)
FROM stock_lock WHERE quantity <= 0
UNION ALL
SELECT 'order_item.quantity', COUNT(*)
FROM order_item WHERE quantity <= 0
UNION ALL
SELECT 'order_item.amounts', COUNT(*)
FROM order_item
WHERE original_price_cent < 0 OR unit_price_cent < 0 OR retail_unit_price_cent < 0
   OR line_original_amount_cent < 0 OR line_amount_cent < 0
   OR unit_cost_cent < 0 OR line_cost_cent < 0
   OR coupon_discount_allocated_cent < 0 OR freight_allocated_cent < 0
   OR paid_amount_allocated_cent < 0
UNION ALL
SELECT 'shop_order.amounts', COUNT(*)
FROM shop_order
WHERE product_original_amount_cent < 0 OR product_amount_cent < 0
   OR coupon_discount_cent < 0 OR freight_cent < 0
   OR payable_amount_cent < 0 OR paid_amount_cent < 0
UNION ALL
SELECT 'stock_log.inventory', COUNT(*)
FROM stock_log WHERE quantity_before < 0 OR quantity_after < 0
UNION ALL
SELECT 'stock_log.balance', COUNT(*)
FROM stock_log
WHERE quantity_after <> CAST(quantity_before AS DECIMAL(20, 0)) + quantity_delta;

SELECT id, spu_id, stock_available, low_stock_threshold, price_cent, original_price_cent, cost_price_cent
FROM product_sku
WHERE stock_available < 0 OR low_stock_threshold < 0
   OR price_cent < 0 OR original_price_cent < 0 OR cost_price_cent < 0
ORDER BY id LIMIT 100;

SELECT order_item_id, COUNT(*) AS row_count
FROM stock_lock GROUP BY order_item_id HAVING COUNT(*) > 1
ORDER BY order_item_id LIMIT 100;

SELECT id, order_id, order_item_id, sku_id, quantity, status
FROM stock_lock WHERE quantity <= 0
   OR order_item_id IN (SELECT order_item_id FROM stock_lock GROUP BY order_item_id HAVING COUNT(*) > 1)
ORDER BY order_item_id, id LIMIT 100;

SELECT id, order_id, quantity, original_price_cent, unit_price_cent, retail_unit_price_cent,
       line_original_amount_cent, line_amount_cent, unit_cost_cent, line_cost_cent,
       coupon_discount_allocated_cent, freight_allocated_cent, paid_amount_allocated_cent
FROM order_item
WHERE quantity <= 0 OR original_price_cent < 0 OR unit_price_cent < 0 OR retail_unit_price_cent < 0
   OR line_original_amount_cent < 0 OR line_amount_cent < 0
   OR unit_cost_cent < 0 OR line_cost_cent < 0
   OR coupon_discount_allocated_cent < 0 OR freight_allocated_cent < 0
   OR paid_amount_allocated_cent < 0
ORDER BY id LIMIT 100;

SELECT id, product_original_amount_cent, product_amount_cent, coupon_discount_cent,
       freight_cent, payable_amount_cent, paid_amount_cent
FROM shop_order
WHERE product_original_amount_cent < 0 OR product_amount_cent < 0
   OR coupon_discount_cent < 0 OR freight_cent < 0
   OR payable_amount_cent < 0 OR paid_amount_cent < 0
ORDER BY id LIMIT 100;

SELECT id, sku_id, order_id, refund_order_id, quantity_before, quantity_delta, quantity_after
FROM stock_log
WHERE quantity_before < 0 OR quantity_after < 0
   OR quantity_after <> CAST(quantity_before AS DECIMAL(20, 0)) + quantity_delta
ORDER BY id LIMIT 100;
