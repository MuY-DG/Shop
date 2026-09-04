ALTER TABLE wechat_express_setting
  ALTER COLUMN message_enabled SET DEFAULT TRUE;

UPDATE wechat_express_setting
SET message_enabled = TRUE
WHERE id = 1
  AND revision = 0
  AND message_enabled = FALSE;
