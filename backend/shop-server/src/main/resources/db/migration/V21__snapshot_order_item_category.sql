-- Freeze the category used at checkout. Historical orders remain unknown because
-- the current product category cannot establish the category at the time of sale.
ALTER TABLE order_item
    ADD COLUMN category_id_snapshot bigint DEFAULT NULL;
ALTER TABLE order_item
    ADD COLUMN category_name_snapshot varchar(64) DEFAULT NULL;
