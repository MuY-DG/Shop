ALTER TABLE app_user
    ADD COLUMN nickname VARCHAR(64) NOT NULL DEFAULT '';

UPDATE app_user
SET nickname = CONCAT('用户', RIGHT(CONCAT('', id), 6))
WHERE nickname = '';
