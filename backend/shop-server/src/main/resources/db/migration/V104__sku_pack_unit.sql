-- SKU 包装单位：与净含量共同派生对外规格文案（如 500g/袋）。
-- 仅后台编辑器使用，小程序通过已拼好的 spec_text 展示。
ALTER TABLE product_sku ADD COLUMN pack_unit_text VARCHAR(24) NULL;
