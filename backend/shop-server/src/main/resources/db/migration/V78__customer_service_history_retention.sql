CREATE INDEX idx_customer_service_message_retention
    ON customer_service_message(created_at, id);
