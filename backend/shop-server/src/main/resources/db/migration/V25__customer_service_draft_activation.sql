ALTER TABLE customer_service_conversation
    ADD COLUMN activated_at TIMESTAMP NULL;

UPDATE customer_service_conversation
SET activated_at = COALESCE(last_message_at, created_at)
WHERE status IN ('WAITING', 'ACTIVE', 'CLOSED');
