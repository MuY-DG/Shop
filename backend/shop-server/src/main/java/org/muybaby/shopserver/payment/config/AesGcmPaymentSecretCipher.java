package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesGcmPaymentSecretCipher implements PaymentSecretCipher {

    private static final String PREFIX = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PaymentProperties properties;

    public AesGcmPaymentSecretCipher(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, plaintext.getBytes(StandardCharsets.UTF_8));
        return PREFIX + ":"
                + Base64.getEncoder().encodeToString(nonce) + ":"
                + Base64.getEncoder().encodeToString(ciphertext);
    }

    @Override
    public String decrypt(String ciphertext) {
        if (!StringUtils.hasText(ciphertext)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String[] parts = ciphertext.split(":", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, nonce, encrypted);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(secretKeyBytes(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private byte[] secretKeyBytes() {
        String secretKey = properties.secretKey();
        if (!StringUtils.hasText(secretKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        byte[] raw = secretKey.getBytes(StandardCharsets.UTF_8);
        if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
            return raw;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(secretKey);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to validation failure without exposing the configured value.
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
