ALTER TABLE coupon_template
    ADD COLUMN distribution_mode VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE coupon_template
    ADD COLUMN audience_user_id BIGINT NULL;

CREATE INDEX idx_coupon_template_distribution_audience
    ON coupon_template(distribution_mode, audience_user_id, id);
