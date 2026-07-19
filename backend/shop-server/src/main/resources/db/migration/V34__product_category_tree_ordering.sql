CREATE TABLE IF NOT EXISTS product_category_guard (
    id TINYINT PRIMARY KEY,
    CONSTRAINT chk_product_category_guard_singleton CHECK (id = 1)
);

INSERT INTO product_category_guard (id)
SELECT 1
WHERE NOT EXISTS (SELECT 1 FROM product_category_guard WHERE id = 1);
