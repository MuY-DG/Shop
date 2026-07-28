ALTER TABLE after_sale_request
    ADD COLUMN after_sale_no VARCHAR(32) NULL;

UPDATE after_sale_request
SET after_sale_no = CONCAT(
        'AS',
        LPAD(CONCAT('', YEAR(created_at)), 4, '0'),
        LPAD(CONCAT('', MONTH(created_at)), 2, '0'),
        LPAD(CONCAT('', DAY(created_at)), 2, '0'),
        LPAD(CONCAT('', HOUR(created_at)), 2, '0'),
        LPAD(CONCAT('', MINUTE(created_at)), 2, '0'),
        LPAD(CONCAT('', SECOND(created_at)), 2, '0'),
        LPAD(CONCAT('', id), 14, '0')
    )
WHERE after_sale_no IS NULL;

ALTER TABLE after_sale_request
    MODIFY COLUMN after_sale_no VARCHAR(32) NOT NULL;

CREATE UNIQUE INDEX uk_after_sale_request_after_sale_no
    ON after_sale_request(after_sale_no);
