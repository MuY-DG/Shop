DROP INDEX uk_order_shipment_order ON order_shipment;

ALTER TABLE order_shipment
    ADD COLUMN package_no INT NOT NULL DEFAULT 1;

ALTER TABLE order_shipment
    ADD COLUMN final_shipment BOOLEAN NOT NULL DEFAULT TRUE;

CREATE UNIQUE INDEX uk_order_shipment_package
    ON order_shipment(order_id, package_no);

CREATE INDEX idx_order_shipment_order
    ON order_shipment(order_id, id);

CREATE TABLE order_shipment_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_order_shipment_item_quantity CHECK (quantity > 0)
);

CREATE UNIQUE INDEX uk_order_shipment_item
    ON order_shipment_item(shipment_id, order_item_id);

CREATE INDEX idx_order_shipment_item_order_item
    ON order_shipment_item(order_item_id, shipment_id);

INSERT INTO order_shipment_item(shipment_id, order_item_id, quantity, created_at)
SELECT shipment.id, item.id, item.quantity, shipment.created_at
FROM order_shipment shipment
JOIN order_item item ON item.order_id = shipment.order_id;

CREATE TABLE order_electronic_waybill_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    electronic_waybill_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_order_electronic_waybill_item_quantity CHECK (quantity > 0)
);

CREATE UNIQUE INDEX uk_order_electronic_waybill_item
    ON order_electronic_waybill_item(electronic_waybill_id, order_item_id);

CREATE INDEX idx_order_electronic_waybill_item_order_item
    ON order_electronic_waybill_item(order_item_id, electronic_waybill_id);

INSERT INTO order_electronic_waybill_item(
    electronic_waybill_id, order_item_id, quantity, created_at
)
SELECT waybill.id, item.id, item.quantity, waybill.created_at
FROM order_electronic_waybill waybill
JOIN order_item item ON item.order_id = waybill.order_id;
