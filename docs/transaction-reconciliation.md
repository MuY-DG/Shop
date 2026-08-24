# 订单期限与退款回补一致性检查

本文适用于 generation 2（V1-V7）数据库的日常只读核对，不再承担旧数据库升级或历史回填说明。所有应用时间戳使用 UTC。

运行查询时使用只读数据库账号，保存脱敏结果与发布证据。任何非零异常都应先隔离具体业务 ID、核对渠道和仓储证据，再通过受审计的业务操作修复；不要执行无边界批量 UPDATE。

## CREATED 订单的支付期限

新创建订单必须固化支付截止时间：

```sql
SELECT id, order_no, created_at
FROM shop_order
WHERE status = 'CREATED'
  AND created_at >= :audit_start_utc
  AND payment_expires_at IS NULL
ORDER BY id;
```

结果必须为空。支付单的截止时间必须与订单一致：

```sql
SELECT
    order_entry.id AS order_id,
    order_entry.payment_expires_at AS order_deadline,
    payment.expires_at AS payment_deadline
FROM shop_order order_entry
JOIN payment_order payment ON payment.order_id = order_entry.id
WHERE order_entry.created_at >= :audit_start_utc
  AND (
      order_entry.payment_expires_at IS NULL
      OR payment.expires_at <> order_entry.payment_expires_at
  )
ORDER BY order_entry.id, payment.id;
```

结果必须为空。

## CREATED 订单的库存锁

未付款订单的订单项与库存锁应能完整对应：

```sql
SELECT
    order_entry.id,
    order_entry.order_no,
    order_entry.created_at,
    order_entry.user_coupon_id,
    COUNT(DISTINCT item.id) AS item_count,
    COUNT(DISTINCT stock_lock_entry.id) AS stock_lock_count,
    SUM(CASE WHEN stock_lock_entry.status = 'LOCKED' THEN 1 ELSE 0 END) AS locked_count
FROM shop_order order_entry
LEFT JOIN order_item item ON item.order_id = order_entry.id
LEFT JOIN stock_lock stock_lock_entry ON stock_lock_entry.order_id = order_entry.id
WHERE order_entry.status = 'CREATED'
  AND order_entry.created_at >= :audit_start_utc
  AND NOT EXISTS (
      SELECT 1 FROM payment_order payment
      WHERE payment.order_id = order_entry.id
  )
GROUP BY order_entry.id, order_entry.order_no, order_entry.created_at, order_entry.user_coupon_id
HAVING item_count = 0
    OR item_count <> stock_lock_count
    OR stock_lock_count <> locked_count
ORDER BY order_entry.id;
```

若有返回行，暂停对应订单的超时处理，核对订单项、SKU、优惠券归属、库存锁和当前可售库存。不要直接补截止时间或伪造库存锁。

## 成功退款的库存回补

对需要回补库存的成功退款，退款记录、全部库存锁和每个 SKU 的聚合库存日志应在同一事务中完成。以下查询必须为空：

```sql
SELECT
    refund.id AS refund_order_id,
    refund.order_id,
    refund.restock_required,
    refund.restocked_at,
    COUNT(stock_lock_entry.id) AS stock_lock_count,
    SUM(
        CASE
            WHEN stock_lock_entry.status = 'RESTOCKED'
             AND stock_lock_entry.restock_refund_order_id = refund.id
             AND stock_lock_entry.restocked_at IS NOT NULL
            THEN 1 ELSE 0
        END
    ) AS restocked_lock_count,
    COUNT(DISTINCT stock_log_entry.sku_id) AS restock_log_sku_count
FROM refund_order refund
LEFT JOIN stock_lock stock_lock_entry ON stock_lock_entry.order_id = refund.order_id
LEFT JOIN stock_log stock_log_entry
       ON stock_log_entry.refund_order_id = refund.id
      AND stock_log_entry.change_type = 'REFUND_RESTOCK'
WHERE refund.status = 'SUCCESS'
  AND refund.restock_required = TRUE
  AND refund.success_at >= :audit_start_utc
GROUP BY
    refund.id,
    refund.order_id,
    refund.restock_required,
    refund.restocked_at
HAVING refund.restocked_at IS NULL
    OR stock_lock_count = 0
    OR stock_lock_count <> restocked_lock_count
    OR restock_log_sku_count = 0
ORDER BY refund.id;
```

`restock_required` 是退款准备阶段固化的业务结论。不要根据当前订单状态事后推测是否应回补；应核对退款请求商品数量、支付/退款渠道证据、发货与退货事实、订单项到库存锁的完整映射。

## 异常处置

若任一不变量查询返回数据：

1. 关闭受影响的 worker 或业务入口，避免异常继续扩大；
2. 保存订单、支付、退款、售后、包裹、库存锁、库存日志和渠道查询的脱敏证据；
3. 明确问题属于渠道状态未同步、本地事务中断、数据被人工修改还是代码缺陷；
4. 通过单笔、带审计、可重放的修复流程处理；
5. 重新运行全部查询并核对前后数量；
6. 补充自动化回归测试和事故记录。

对账页面中的“记录解决”只能更新差异处置状态，不能静默改变订单、退款或库存业务状态。
