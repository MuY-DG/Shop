ALTER TABLE user_address
    ADD COLUMN location_name VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE user_address
    ADD COLUMN doorplate VARCHAR(128) NOT NULL DEFAULT '';
