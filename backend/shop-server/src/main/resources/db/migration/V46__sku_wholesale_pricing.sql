CREATE TABLE product_sku_wholesale_tier (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    min_quantity INT NOT NULL,
    unit_price_cent BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sku_wholesale_tier_quantity UNIQUE (sku_id, min_quantity),
    CONSTRAINT fk_sku_wholesale_tier_sku
        FOREIGN KEY (sku_id) REFERENCES product_sku(id) ON DELETE CASCADE
);

CREATE INDEX idx_sku_wholesale_tier_lookup
    ON product_sku_wholesale_tier(sku_id, min_quantity);

ALTER TABLE order_item
    ADD COLUMN retail_unit_price_cent BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_item
    ADD COLUMN wholesale_tier_min_quantity INT NULL;

UPDATE order_item
SET retail_unit_price_cent = unit_price_cent
WHERE retail_unit_price_cent = 0;
