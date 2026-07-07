CREATE TABLE cart_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cart_item_user_sku UNIQUE (user_id, sku_id)
);

CREATE INDEX idx_cart_item_user_updated ON cart_item(user_id, updated_at);
CREATE INDEX idx_cart_item_sku ON cart_item(sku_id);
