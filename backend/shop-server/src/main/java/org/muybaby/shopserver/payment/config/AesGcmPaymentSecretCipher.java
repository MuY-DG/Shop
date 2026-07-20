package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.muybaby.shopserver.payment.PaymentSecretEncryptionProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AesGcmPaymentSecretCipher implements PaymentSecretCipher {

    private static final String V1_PREFIX = "v1";
    private static final String V2_PREFIX = "v2";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int MAX_KEY_RING_SIZE = 16;
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern CONTEXT_PART_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PaymentProperties paymentProperties;
    private final int writeVersion;
    private final String activeKeyId;
    private final Map<String, byte[]> keyRing;

    public AesGcmPaymentSecretCipher(
            PaymentProperties paymentProperties,
            PaymentSecretEncryptionProperties encryptionProperties
    ) {
        this.paymentProperties = paymentProperties;
        this.writeVersion = encryptionProperties.effectiveWriteVersion();
        this.activeKeyId = normalizeKeyId(encryptionProperties.activeKeyId());
        this.keyRing = parseKeyRing(encryptionProperties.keyRing());
        validateWriteConfiguration();
    }

    @Override
    public EncryptedSecret encrypt(SecretContext context, String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw validationFailure();
        }
        SecretContext requiredContext = requireContext(context);
        if (writeVersion == 1) {
            return encryptV1(plaintext);
        }
        return encryptV2(requiredContext, plaintext);
    }

    @Override
    public DecryptedSecret decrypt(SecretContext context, String ciphertext) {
        if (!StringUtils.hasText(ciphertext)) {
            throw validationFailure();
        }
        String[] versionParts = ciphertext.split(":", 2);
        if (versionParts.length != 2) {
            throw validationFailure();
        }
        return switch (versionParts[0]) {
            case V1_PREFIX -> decryptV1(ciphertext);
            case V2_PREFIX -> decryptV2(requireContext(context), ciphertext);
            default -> throw validationFailure();
        };
    }

    @Override
    public boolean shouldReencrypt(int cipherVersion, String keyId) {
        if (writeVersion != 2) {
            return false;
        }
        return cipherVersion != 2 || !MessageDigest.isEqual(
                activeKeyId.getBytes(StandardCharsets.UTF_8),
                normalizeKeyId(keyId).getBytes(StandardCharsets.UTF_8));
    }

    private EncryptedSecret encryptV1(String plaintext) {
        byte[] nonce = randomNonce();
        byte[] ciphertext = crypt(
                Cipher.ENCRYPT_MODE,
                legacyKeyBytes(),
                nonce,
                plaintext.getBytes(StandardCharsets.UTF_8),
                null
        );
        return new EncryptedSecret(
                V1_PREFIX + ":"
                        + Base64.getEncoder().encodeToString(nonce) + ":"
                        + Base64.getEncoder().encodeToString(ciphertext),
                1,
                ""
        );
    }

    private EncryptedSecret encryptV2(SecretContext context, String plaintext) {
        byte[] key = keyRing.get(activeKeyId);
        if (key == null) {
            throw validationFailure();
        }
        byte[] nonce = randomNonce();
        byte[] ciphertext = crypt(
                Cipher.ENCRYPT_MODE,
                key,
                nonce,
                plaintext.getBytes(StandardCharsets.UTF_8),
                aad(context, activeKeyId)
        );
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return new EncryptedSecret(
                V2_PREFIX + ":" + activeKeyId + ":"
                        + encoder.encodeToString(nonce) + ":"
                        + encoder.encodeToString(ciphertext),
                2,
                activeKeyId
        );
    }

    private DecryptedSecret decryptV1(String ciphertext) {
        String[] parts = ciphertext.split(":", 3);
        if (parts.length != 3 || !V1_PREFIX.equals(parts[0])) {
            throw validationFailure();
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            requireEnvelopeLengths(nonce, encrypted);
            byte[] plaintext = crypt(
                    Cipher.DECRYPT_MODE, legacyKeyBytes(), nonce, encrypted, null);
            return new DecryptedSecret(
                    new String(plaintext, StandardCharsets.UTF_8), 1, "");
        } catch (IllegalArgumentException ex) {
            throw validationFailure();
        }
    }

    private DecryptedSecret decryptV2(SecretContext context, String ciphertext) {
        String[] parts = ciphertext.split(":", 4);
        if (parts.length != 4 || !V2_PREFIX.equals(parts[0])) {
            throw validationFailure();
        }
        try {
            String keyId = requireKeyId(parts[1]);
            byte[] key = keyRing.get(keyId);
            if (key == null) {
                throw validationFailure();
            }
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[3]);
            requireEnvelopeLengths(nonce, encrypted);
            byte[] plaintext = crypt(
                    Cipher.DECRYPT_MODE, key, nonce, encrypted, aad(context, keyId));
            return new DecryptedSecret(
                    new String(plaintext, StandardCharsets.UTF_8), 2, keyId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw validationFailure();
        }
    }

    private byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] input, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw validationFailure();
        }
    }

    private byte[] aad(SecretContext context, String keyId) {
        return ("shop-secret|v2|" + keyId + "|" + context.domain()
                + "|" + context.rowIdentity() + "|" + context.fieldName())
                .getBytes(StandardCharsets.UTF_8);
    }

    private SecretContext requireContext(SecretContext context) {
        if (context == null
                || !validContextPart(context.domain())
                || !validContextPart(context.rowIdentity())
                || !validContextPart(context.fieldName())) {
            throw validationFailure();
        }
        return context;
    }

    private boolean validContextPart(String value) {
        return value != null && CONTEXT_PART_PATTERN.matcher(value).matches();
    }

    private void validateWriteConfiguration() {
        if (writeVersion != 1 && writeVersion != 2) {
            throw new IllegalStateException("Payment secret write version must be 1 or 2");
        }
        if (writeVersion == 2 && (!StringUtils.hasText(activeKeyId) || !keyRing.containsKey(activeKeyId))) {
            throw new IllegalStateException("Payment secret active key is missing from the key ring");
        }
    }

    private Map<String, byte[]> parseKeyRing(String encodedRing) {
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        if (!StringUtils.hasText(encodedRing)) {
            return Map.of();
        }
        for (String entry : encodedRing.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException("Invalid payment secret key-ring entry");
            }
            String keyId = requireKeyId(entry.substring(0, separator).trim());
            String encodedKey = entry.substring(separator + 1).trim();
            if (!encodedKey.startsWith("base64:")) {
                throw new IllegalStateException("Payment secret key-ring values must use base64 encoding");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(encodedKey.substring("base64:".length()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Invalid payment secret key-ring value", ex);
            }
            if (key.length != 32 || parsed.putIfAbsent(keyId, key) != null) {
                throw new IllegalStateException("Payment secret key-ring keys must be unique AES-256 keys");
            }
            if (parsed.size() > MAX_KEY_RING_SIZE) {
                throw new IllegalStateException("Payment secret key ring is too large");
            }
        }
        return Map.copyOf(parsed);
    }

    private String requireKeyId(String value) {
        String normalized = normalizeKeyId(value);
        if (!KEY_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("Invalid payment secret key id");
        }
        return normalized;
    }

    private String normalizeKeyId(String value) {
        return value == null ? "" : value.trim();
    }

    private byte[] legacyKeyBytes() {
        String secretKey = paymentProperties.secretKey();
        if (!StringUtils.hasText(secretKey)) {
            throw validationFailure();
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
            // Fall through without exposing configured key material.
        }
        throw validationFailure();
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    private void requireEnvelopeLengths(byte[] nonce, byte[] ciphertext) {
        if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BYTES) {
            throw validationFailure();
        }
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
