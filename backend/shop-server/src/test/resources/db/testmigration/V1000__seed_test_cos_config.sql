INSERT INTO storage_runtime_setting
    (id, cos_public_base_url, cos_region, cos_bucket,
     cos_secret_id_ciphertext, cos_secret_key_ciphertext,
     secret_cipher_version, secret_key_id, secret_revision)
VALUES
    (1, 'https://shop-test-1250000000.cos.ap-guangzhou.myqcloud.com',
     'ap-guangzhou', 'shop-test-1250000000',
     'v1:AQEBAQEBAQEBAQEB:f1+EehISDHXRrwxHMX5lDZ4Kf3GHbArRGplNWklK66RmyJ0=',
     'v1:AgICAgICAgICAgIC:vGn1iNMuEUgnyFH05I6HYDEjAtNgmLcesrB364SH+Q==',
     1, '', 1);
