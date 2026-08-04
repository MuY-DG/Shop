CREATE TABLE product_review_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_review_image_asset UNIQUE (asset_id),
    CONSTRAINT uk_product_review_image_sort UNIQUE (review_id, sort_order),
    CONSTRAINT fk_product_review_image_review FOREIGN KEY (review_id)
        REFERENCES product_review (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_review_image_asset FOREIGN KEY (asset_id)
        REFERENCES storage_asset (id) ON DELETE CASCADE,
    INDEX idx_product_review_image_review (review_id, sort_order, id)
);
