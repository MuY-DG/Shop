ALTER TABLE product_spu
    ADD COLUMN compliance_type VARCHAR(20) NOT NULL DEFAULT 'UNCLASSIFIED';

ALTER TABLE product_spu
    ADD CONSTRAINT chk_product_spu_compliance_type CHECK (
        compliance_type IN ('UNCLASSIFIED', 'FOOD', 'NON_FOOD')
    );

UPDATE product_spu
SET compliance_type = 'UNCLASSIFIED';

ALTER TABLE product_sku
    ADD COLUMN net_content_text VARCHAR(64) NOT NULL DEFAULT '';

CREATE TABLE product_food_disclosure (
    spu_id BIGINT PRIMARY KEY,
    food_name VARCHAR(160) NOT NULL DEFAULT '',
    ingredients TEXT NOT NULL,
    allergen_information VARCHAR(1000) NOT NULL DEFAULT '',
    storage_conditions VARCHAR(500) NOT NULL DEFAULT '',
    shelf_life_description VARCHAR(255) NOT NULL DEFAULT '',
    manufacturer_name VARCHAR(160) NOT NULL DEFAULT '',
    manufacturer_address VARCHAR(512) NOT NULL DEFAULT '',
    production_license_number VARCHAR(96) NOT NULL DEFAULT '',
    origin VARCHAR(160) NOT NULL DEFAULT '',
    consumer_notice VARCHAR(1000) NOT NULL DEFAULT '',
    variable_production_notice VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_food_disclosure_spu FOREIGN KEY (spu_id)
        REFERENCES product_spu(id)
);

CREATE TABLE product_food_disclosure_label (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spu_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_food_label_disclosure FOREIGN KEY (spu_id)
        REFERENCES product_food_disclosure(spu_id),
    CONSTRAINT fk_product_food_label_asset FOREIGN KEY (file_id)
        REFERENCES storage_asset(id),
    CONSTRAINT uk_product_food_label_file UNIQUE (spu_id, file_id),
    CONSTRAINT chk_product_food_label_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_product_food_label_spu_sort
    ON product_food_disclosure_label(spu_id, sort_order, id);
