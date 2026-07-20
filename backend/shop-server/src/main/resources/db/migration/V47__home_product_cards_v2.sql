DROP TABLE product_spu_tag;

ALTER TABLE home_product_item
    DROP CONSTRAINT chk_home_product_item_badge_mode;

ALTER TABLE home_product_item
    DROP COLUMN badge_mode;

ALTER TABLE home_product_item
    DROP COLUMN custom_badge_text;

ALTER TABLE product_spu
    ADD COLUMN display_badge_text VARCHAR(24) NOT NULL DEFAULT '';

ALTER TABLE product_spu
    ADD COLUMN display_badge_tone VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL';

ALTER TABLE product_spu
    ADD CONSTRAINT chk_product_spu_display_badge_tone
        CHECK (display_badge_tone IN ('RED', 'ORANGE', 'GREEN', 'NEUTRAL'));

ALTER TABLE product_parameter_definition
    ADD COLUMN card_role VARCHAR(16) NOT NULL DEFAULT 'META';

ALTER TABLE product_parameter_definition
    ADD COLUMN card_renderer VARCHAR(16) NOT NULL DEFAULT 'TEXT';

ALTER TABLE product_parameter_definition
    ADD COLUMN card_priority INT NOT NULL DEFAULT 0;

ALTER TABLE product_parameter_definition
    ADD CONSTRAINT chk_product_parameter_definition_card_role
        CHECK (card_role IN ('HIGHLIGHT', 'META'));

ALTER TABLE product_parameter_definition
    ADD CONSTRAINT chk_product_parameter_definition_card_renderer
        CHECK (card_renderer IN ('TEXT', 'PILL', 'LEVEL', 'SPICE'));

ALTER TABLE product_parameter_definition
    ADD CONSTRAINT chk_product_parameter_definition_card_priority
        CHECK (card_priority >= 0);
