CREATE TABLE product_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    rating INT NOT NULL,
    content VARCHAR(1000) NOT NULL DEFAULT '',
    anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_review_order_item UNIQUE (order_item_id)
);

CREATE INDEX idx_product_review_spu_status_created
    ON product_review(spu_id, status, created_at, id);
CREATE INDEX idx_product_review_user_created
    ON product_review(user_id, created_at, id);

CREATE TABLE user_product_favorite (
    user_id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, spu_id)
);

CREATE INDEX idx_user_product_favorite_user_created
    ON user_product_favorite(user_id, created_at, spu_id);

CREATE TABLE user_product_browse_history (
    user_id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    first_viewed_at TIMESTAMP NOT NULL,
    last_viewed_at TIMESTAMP NOT NULL,
    view_count BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, spu_id)
);

CREATE INDEX idx_user_product_history_user_last_viewed
    ON user_product_browse_history(user_id, last_viewed_at, spu_id);
