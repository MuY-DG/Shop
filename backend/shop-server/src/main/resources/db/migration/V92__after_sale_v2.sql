ALTER TABLE after_sale_request
    ADD COLUMN flow_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE after_sale_request
    ADD COLUMN request_key VARCHAR(80) NULL;

ALTER TABLE after_sale_request
    ADD COLUMN request_digest VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE after_sale_request
    ADD COLUMN source_order_status VARCHAR(20) NOT NULL DEFAULT '';

ALTER TABLE after_sale_request
    ADD COLUMN return_deadline_at TIMESTAMP NULL;

ALTER TABLE after_sale_request
    ADD COLUMN cancelled_at TIMESTAMP NULL;

ALTER TABLE after_sale_request
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uk_after_sale_request_user_order_key
    ON after_sale_request(user_id, order_id, request_key);

CREATE INDEX idx_after_sale_request_order_flow_status
    ON after_sale_request(order_id, flow_version, status, id);

CREATE TABLE after_sale_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    order_quantity_snapshot INT NOT NULL,
    paid_amount_basis_cent BIGINT NOT NULL,
    refunded_quantity_before INT NOT NULL DEFAULT 0,
    requested_quantity INT NOT NULL,
    approved_quantity INT NULL,
    requested_amount_cent BIGINT NOT NULL,
    approved_amount_cent BIGINT NULL,
    restock_quantity INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_after_sale_item_sale_order_item
    ON after_sale_item(after_sale_id, order_item_id);

CREATE INDEX idx_after_sale_item_order_item_sale
    ON after_sale_item(order_item_id, after_sale_id);

ALTER TABLE after_sale_item
    ADD CONSTRAINT chk_after_sale_item_quantities CHECK (
        order_quantity_snapshot > 0
        AND refunded_quantity_before >= 0
        AND refunded_quantity_before <= order_quantity_snapshot
        AND requested_quantity > 0
        AND requested_quantity <= order_quantity_snapshot - refunded_quantity_before
        AND (approved_quantity IS NULL OR (
            approved_quantity >= 0 AND approved_quantity <= requested_quantity
        ))
        AND restock_quantity >= 0
        AND restock_quantity <= COALESCE(approved_quantity, 0)
    );

ALTER TABLE after_sale_item
    ADD CONSTRAINT chk_after_sale_item_amounts CHECK (
        paid_amount_basis_cent >= 0
        AND requested_amount_cent >= 0
        AND requested_amount_cent <= paid_amount_basis_cent
        AND (approved_amount_cent IS NULL OR (
            approved_amount_cent >= 0
            AND approved_amount_cent <= requested_amount_cent
            AND approved_amount_cent <= paid_amount_basis_cent
        ))
    );

CREATE TABLE merchant_return_address (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contact_name VARCHAR(64) NOT NULL,
    contact_phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL DEFAULT '',
    city VARCHAR(64) NOT NULL DEFAULT '',
    district VARCHAR(64) NOT NULL DEFAULT '',
    detail_address VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    default_slot SMALLINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_merchant_return_address_default_slot
    ON merchant_return_address(default_slot);

CREATE INDEX idx_merchant_return_address_enabled_updated
    ON merchant_return_address(enabled, updated_at, id);

ALTER TABLE merchant_return_address
    ADD CONSTRAINT chk_merchant_return_address_default CHECK (
        default_slot IS NULL OR default_slot = 1
    );

CREATE TABLE after_sale_return (
    after_sale_id BIGINT PRIMARY KEY,
    return_address_id BIGINT NOT NULL,
    contact_name VARCHAR(64) NOT NULL,
    contact_phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL DEFAULT '',
    city VARCHAR(64) NOT NULL DEFAULT '',
    district VARCHAR(64) NOT NULL DEFAULT '',
    detail_address VARCHAR(255) NOT NULL,
    delivery_company_code VARCHAR(128) NOT NULL DEFAULT '',
    delivery_company_name VARCHAR(128) NOT NULL DEFAULT '',
    tracking_no VARCHAR(80) NOT NULL DEFAULT '',
    user_shipped_at TIMESTAMP NULL,
    merchant_received_at TIMESTAMP NULL,
    inspection_result VARCHAR(20) NOT NULL DEFAULT '',
    inspection_note VARCHAR(255) NOT NULL DEFAULT '',
    inspected_by BIGINT NULL,
    inspected_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_after_sale_return_tracking
    ON after_sale_return(delivery_company_code, tracking_no);

CREATE TABLE after_sale_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    from_status VARCHAR(32) NOT NULL DEFAULT '',
    to_status VARCHAR(32) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    operator_type VARCHAR(20) NOT NULL,
    operator_id BIGINT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_after_sale_status_log_sale_created
    ON after_sale_status_log(after_sale_id, created_at, id);

CREATE TABLE refund_inventory_restock_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    refund_order_id BIGINT NOT NULL,
    after_sale_item_id BIGINT NULL,
    order_item_id BIGINT NOT NULL,
    stock_lock_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    restocked_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_refund_restock_order_item
    ON refund_inventory_restock_item(refund_order_id, order_item_id);

CREATE INDEX idx_refund_restock_stock_lock
    ON refund_inventory_restock_item(stock_lock_id, refund_order_id);

ALTER TABLE refund_inventory_restock_item
    ADD CONSTRAINT chk_refund_restock_quantity CHECK (quantity > 0);

ALTER TABLE shop_order
    ADD COLUMN refund_status VARCHAR(32) NOT NULL DEFAULT 'NONE';

ALTER TABLE shop_order
    ADD COLUMN refunded_amount_cent BIGINT NOT NULL DEFAULT 0;

ALTER TABLE shop_order
    ADD COLUMN last_refund_success_at TIMESTAMP NULL;

CREATE INDEX idx_shop_order_refund_status_updated
    ON shop_order(refund_status, updated_at, id);

ALTER TABLE order_item
    ADD COLUMN refunded_quantity INT NOT NULL DEFAULT 0;

ALTER TABLE stock_lock
    ADD COLUMN restocked_quantity INT NOT NULL DEFAULT 0;

ALTER TABLE shop_order
    ADD CONSTRAINT chk_shop_order_refunded_amount CHECK (
        refunded_amount_cent >= 0 AND refunded_amount_cent <= paid_amount_cent
    );

ALTER TABLE order_item
    ADD CONSTRAINT chk_order_item_refunded_quantity CHECK (
        refunded_quantity >= 0 AND refunded_quantity <= quantity
    );

ALTER TABLE stock_lock
    ADD CONSTRAINT chk_stock_lock_restocked_quantity CHECK (
        restocked_quantity >= 0 AND restocked_quantity <= quantity
    );

UPDATE after_sale_request
SET source_order_status = (
    SELECT CASE
               WHEN order_entry.status IN ('REFUNDING', 'REFUNDED')
                    AND order_entry.completed_at IS NOT NULL THEN 'COMPLETED'
               WHEN order_entry.status IN ('REFUNDING', 'REFUNDED')
                    AND order_entry.shipped_at IS NOT NULL THEN 'SHIPPED'
               WHEN order_entry.status IN ('REFUNDING', 'REFUNDED') THEN 'PAID'
               ELSE order_entry.status
           END
    FROM shop_order order_entry
    WHERE order_entry.id = after_sale_request.order_id
)
WHERE source_order_status = ''
  AND EXISTS (
      SELECT 1 FROM shop_order order_entry
      WHERE order_entry.id = after_sale_request.order_id
  );

UPDATE stock_lock
SET restocked_quantity = quantity
WHERE status = 'RESTOCKED';

UPDATE order_item
SET refunded_quantity = quantity
WHERE EXISTS (
    SELECT 1 FROM shop_order order_entry
    WHERE order_entry.id = order_item.order_id
      AND order_entry.status = 'REFUNDED'
);

UPDATE shop_order
SET refunded_amount_cent = COALESCE((
        SELECT SUM(refund.refund_amount_cent)
        FROM refund_order refund
        WHERE refund.order_id = shop_order.id
          AND refund.status = 'SUCCESS'
    ), 0),
    last_refund_success_at = (
        SELECT MAX(refund.success_at)
        FROM refund_order refund
        WHERE refund.order_id = shop_order.id
          AND refund.status = 'SUCCESS'
    ),
    refund_status = CASE
        WHEN shop_order.status = 'REFUNDED' THEN 'FULLY_REFUNDED'
        WHEN shop_order.status = 'REFUNDING' THEN 'FULL_REFUNDING'
        WHEN EXISTS (
            SELECT 1 FROM refund_order refund
            WHERE refund.order_id = shop_order.id
              AND refund.status = 'PROCESSING'
        ) THEN 'PARTIAL_REFUNDING'
        WHEN EXISTS (
            SELECT 1 FROM refund_order refund
            WHERE refund.order_id = shop_order.id
              AND refund.status = 'SUCCESS'
        ) THEN 'PARTIALLY_REFUNDED'
        WHEN EXISTS (
            SELECT 1 FROM refund_order refund
            WHERE refund.order_id = shop_order.id
              AND refund.status = 'FAILED'
        ) THEN 'REFUND_FAILED'
        ELSE 'NONE'
    END;

INSERT INTO refund_inventory_restock_item (
    refund_order_id, after_sale_item_id, order_item_id, stock_lock_id,
    sku_id, quantity, restocked_at, created_at
)
SELECT stock.restock_refund_order_id,
       NULL,
       stock.order_item_id,
       stock.id,
       stock.sku_id,
       stock.quantity,
       COALESCE(stock.restocked_at, CURRENT_TIMESTAMP),
       COALESCE(stock.restocked_at, CURRENT_TIMESTAMP)
FROM stock_lock stock
WHERE stock.restock_refund_order_id IS NOT NULL
  AND stock.status = 'RESTOCKED';

INSERT INTO admin_permission (id, auth_mark, title)
VALUES (8203, 'aftersale:return-address:write', '维护售后退货地址');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_permission.role_id, 8203
FROM admin_role_permission role_permission
WHERE role_permission.permission_id = 8202
  AND NOT EXISTS (
      SELECT 1 FROM admin_role_permission existing
      WHERE existing.role_id = role_permission.role_id
        AND existing.permission_id = 8203
  );

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 821, 8203
WHERE NOT EXISTS (
    SELECT 1 FROM admin_menu_permission existing
    WHERE existing.menu_id = 821
      AND existing.permission_id = 8203
);
