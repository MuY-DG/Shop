-- Test-only bootstrap identity. Production keeps the V7 Super sentinel disabled.
UPDATE admin_user
SET password_hash = '$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i',
    status = 'ENABLED',
    max_sessions = 0
WHERE id = 1;

INSERT INTO storage_runtime_setting
    (id, cos_public_base_url, cos_region, cos_bucket,
     cos_secret_id_ciphertext, cos_secret_key_ciphertext,
     secret_cipher_version, secret_key_id, secret_revision)
VALUES
    (1, 'https://shop-test-1250000000.cos.ap-guangzhou.myqcloud.com',
     'ap-guangzhou', 'shop-test-1250000000',
     'v2:test-main:AQEBAQEBAQEBAQEB:NAuKLlf9HY4Wm5fj2F0ZHZEMiPtcC7xWzn2ZtTxnRBWBUcY',
     'v2:test-main:AgICAgICAgICAgIC:MJ1_mRUAp0b2ma5SwZ2tzKUykzo-_TyAv3_FMpXkaQ',
     2, 'test-main', 1);
