UPDATE product_parameter_definition
SET filterable = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE filterable = TRUE
  AND value_type NOT IN ('SINGLE_SELECT', 'MULTI_SELECT');

ALTER TABLE product_parameter_definition
    ADD CONSTRAINT chk_product_parameter_definition_filterable_type
        CHECK (filterable = FALSE OR value_type IN ('SINGLE_SELECT', 'MULTI_SELECT'));
