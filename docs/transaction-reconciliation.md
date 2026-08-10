# Order Deadline And Refund Restock Reconciliation

Migrations `V85` and `V86` deliberately do not rewrite historical commerce rows. Run every query below with a read-only database account, keep the export with the release evidence, and investigate each non-zero anomaly before enabling the new timeout worker or refund-restock path. All application timestamps are UTC.

## Before Deploying V85

Classify existing `CREATED` orders before assigning any deadline:

```sql
SELECT
    CASE
        WHEN EXISTS (
            SELECT 1 FROM payment_order payment
            WHERE payment.order_id = order_entry.id
        ) THEN 'HAS_PAYMENT_ROW'
        ELSE 'NO_PAYMENT_ROW'
    END AS payment_evidence,
    COUNT(*) AS order_count,
    MIN(order_entry.created_at) AS oldest_created_at,
    MAX(order_entry.created_at) AS newest_created_at
FROM shop_order order_entry
WHERE order_entry.status = 'CREATED'
GROUP BY payment_evidence;
```

`HAS_PAYMENT_ROW` is an inconsistent state and must be reconciled against the payment provider and local payment history. Do not let the new `CREATED` scanner close it. For `NO_PAYMENT_ROW`, export the detailed lock/coupon evidence:

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

Any returned row is quarantined for manual repair. Do not bulk-fill `payment_expires_at` until payment evidence, item-to-lock mapping, coupon ownership, and current sellable stock have been reviewed. If the merchant approves a one-time deadline assignment for clean historical rows, execute it as a separately reviewed production change with an exported target-ID list and before/after counts. `V85` itself leaves historical deadlines null, so the scanner ignores them.

After the application is deployed, new `CREATED` orders must satisfy:

```sql
SELECT id, order_no, created_at
FROM shop_order
WHERE status = 'CREATED'
  AND created_at >= :deployment_utc
  AND payment_expires_at IS NULL
ORDER BY id;
```

The result must be empty. New payment rows must inherit the exact order deadline:

```sql
SELECT
    order_entry.id AS order_id,
    order_entry.payment_expires_at AS order_deadline,
    payment.expires_at AS payment_deadline
FROM shop_order order_entry
JOIN payment_order payment ON payment.order_id = order_entry.id
WHERE order_entry.created_at >= :deployment_utc
  AND (
      order_entry.payment_expires_at IS NULL
      OR payment.expires_at <> order_entry.payment_expires_at
  )
ORDER BY order_entry.id, payment.id;
```

The result must be empty.

## Before Deploying V86

The following query reports historical successful refunds that may represent paid-unshipped inventory. It is a candidate report only; it is not authority to change stock:

```sql
SELECT
    refund.id AS refund_order_id,
    refund.out_refund_no,
    refund.order_id,
    refund.success_at,
    order_entry.status AS order_status,
    COUNT(stock_lock_entry.id) AS stock_lock_count,
    SUM(CASE WHEN stock_lock_entry.status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmed_lock_count
FROM refund_order refund
JOIN shop_order order_entry ON order_entry.id = refund.order_id
LEFT JOIN stock_lock stock_lock_entry ON stock_lock_entry.order_id = refund.order_id
WHERE refund.status = 'SUCCESS'
  AND order_entry.shipped_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM order_shipment shipment
      WHERE shipment.order_id = refund.order_id
  )
GROUP BY
    refund.id,
    refund.out_refund_no,
    refund.order_id,
    refund.success_at,
    order_entry.status
HAVING stock_lock_count > 0
   AND stock_lock_count = confirmed_lock_count
ORDER BY refund.id;
```

For every candidate, confirm the provider refund, shipment/warehouse history, complete order-item-to-stock-lock mapping, and whether the goods were physically returned or never dispatched. Historical refunds are never automatically restocked by `V86`. Any approved correction must use a separately reviewed inventory-adjustment procedure that records before/delta/after quantities and the operator; do not forge `REFUND_RESTOCK` rows or mark historical locks `RESTOCKED` by hand.

## Post-Deployment Invariants

For refunds prepared after deployment, unshipped `PAID` orders snapshot `restock_required = TRUE`; shipped or completed orders snapshot `FALSE`. A verified successful required restock commits the refund state, all stock-lock transitions, SKU increments, and one aggregate stock log per SKU in the same transaction.

This query must return no rows:

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

If an invariant query returns rows, disable the affected worker or refund operation, preserve database and provider evidence, and reconcile the specific IDs. Do not repair aggregate state with an unbounded SQL update.
