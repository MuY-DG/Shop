-- Separate cancellation of unshipped units from refunds of units already shipped.
-- shipment_item_id = 0 denotes no parcel (UNSHIPPED / LEGACY_UNKNOWN).
CREATE TABLE after_sale_fulfillment_allocation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    after_sale_item_id BIGINT NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    shipment_item_id BIGINT NOT NULL DEFAULT 0,
    quantity INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_after_sale_fulfillment_source UNIQUE (after_sale_item_id, source_type, shipment_item_id),
    CONSTRAINT chk_after_sale_fulfillment_quantity CHECK (quantity > 0),
    CONSTRAINT chk_after_sale_fulfillment_source CHECK (
        (source_type = 'SHIPPED' AND shipment_item_id > 0)
        OR (source_type IN ('UNSHIPPED', 'LEGACY_UNKNOWN') AND shipment_item_id = 0)
    ),
    INDEX idx_after_sale_fulfillment_shipment_item (shipment_item_id)
);

-- Only a recorded pre-shipment request can establish historical cancellation.
-- Other legacy refunds retain an explicit unknown origin; never invent a parcel.
INSERT INTO after_sale_fulfillment_allocation
    (after_sale_item_id, source_type, shipment_item_id, quantity, created_at)
SELECT i.id,
       CASE WHEN r.after_sale_type = 'REFUND_ONLY' AND r.source_order_status = 'PAID'
                  AND NOT EXISTS (
                      SELECT 1 FROM order_shipment s
                      WHERE s.order_id = r.order_id
                        AND (s.shipped_at IS NULL OR s.shipped_at <= r.created_at)
                  )
            THEN 'UNSHIPPED' ELSE 'LEGACY_UNKNOWN' END,
       0, i.approved_quantity, i.created_at
FROM after_sale_item i
JOIN after_sale_request r ON r.id = i.after_sale_id
WHERE i.approved_quantity > 0;

ALTER TABLE after_sale_item ADD COLUMN received_quantity INT NULL;
ALTER TABLE after_sale_item ADD CONSTRAINT chk_after_sale_item_received CHECK (
    received_quantity IS NULL OR (
        received_quantity >= 0 AND received_quantity <= COALESCE(approved_quantity, 0)
        AND restock_quantity <= received_quantity
    )
);

-- An in-flight WeChat upload must finish its old claim before refreshing its payload.
ALTER TABLE order_shipment
    ADD COLUMN wechat_upload_refresh_pending BOOLEAN NOT NULL DEFAULT FALSE;
