ALTER TABLE payment_config
    ADD COLUMN private_key_pem_ciphertext TEXT NULL;

ALTER TABLE payment_config
    ADD COLUMN wechat_public_key_pem_ciphertext TEXT NULL;
