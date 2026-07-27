ALTER TABLE payment_order
    ADD COLUMN provider_description VARCHAR(127) NULL;

ALTER TABLE refund_order
    ADD COLUMN provider_reason VARCHAR(80) NULL;
