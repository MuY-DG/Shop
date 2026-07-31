DELETE FROM payment_secret_rotation_checkpoint
WHERE checkpoint_name = 'image-compression-runtime-setting';

DROP TABLE IF EXISTS image_compression_reservation;
DROP TABLE IF EXISTS image_compression_runtime_setting;
