ALTER TABLE product_spu ALTER COLUMN compliance_type SET DEFAULT 'NON_FOOD';

UPDATE product_spu
SET compliance_type = 'NON_FOOD'
WHERE compliance_type = 'UNCLASSIFIED';

ALTER TABLE product_spu DROP CONSTRAINT chk_product_spu_compliance_type;
ALTER TABLE product_spu
    ADD CONSTRAINT chk_product_spu_compliance_type CHECK (compliance_type IN ('NON_FOOD', 'FOOD'));
