CREATE TEMPORARY TABLE tmp_wechat_service_card_failed_suffix (
    delivery_id BIGINT PRIMARY KEY
);

INSERT INTO tmp_wechat_service_card_failed_suffix (delivery_id)
SELECT queued.id
FROM wechat_service_card_delivery queued
WHERE queued.state IN ('PENDING', 'SENDING', 'UNKNOWN', 'RECONCILING')
  AND EXISTS (
      SELECT 1
      FROM wechat_service_card_delivery failed
      WHERE failed.card_id = queued.card_id
        AND failed.sequence_no < queued.sequence_no
        AND failed.state = 'FAILED'
  );

UPDATE wechat_service_card_delivery
SET state = 'SKIPPED',
    claim_token = NULL,
    claimed_at = NULL,
    next_action_at = NULL,
    provider_error_code = 'PREDECESSOR_FAILED',
    provider_error_message = 'An earlier WeChat service-card update failed',
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT delivery_id
    FROM tmp_wechat_service_card_failed_suffix
);

DROP TABLE tmp_wechat_service_card_failed_suffix;
