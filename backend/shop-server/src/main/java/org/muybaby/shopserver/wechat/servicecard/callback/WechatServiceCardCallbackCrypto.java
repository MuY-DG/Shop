package org.muybaby.shopserver.wechat.servicecard.callback;

import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Component
public class WechatServiceCardCallbackCrypto {

    private final WechatServiceCardProperties properties;
    private final WechatMiniProgramProperties miniProgramProperties;

    public WechatServiceCardCallbackCrypto(
            WechatServiceCardProperties properties,
            WechatMiniProgramProperties miniProgramProperties
    ) {
        this.properties = properties;
        this.miniProgramProperties = miniProgramProperties;
    }

    public boolean verifyHandshake(
            String signature,
            String timestamp,
            String nonce
    ) {
        return validSignature(signature, List.of(
                properties.callback().token(), timestamp, nonce
        ));
    }

    public boolean verifyEncrypted(
            String signature,
            String timestamp,
            String nonce,
            String encrypted
    ) {
        return validSignature(signature, List.of(
                properties.callback().token(), timestamp, nonce, encrypted
        ));
    }

    public String decrypt(String encrypted) {
        if (!properties.callback().secureReady()
                || !StringUtils.hasText(miniProgramProperties.appId())
                || !StringUtils.hasText(encrypted)) {
            throw new IllegalArgumentException("WeChat callback encryption is not configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(
                    properties.callback().encodingAesKey().trim() + "="
            );
            if (key.length != 32) {
                throw new IllegalArgumentException("WeChat callback AES key must decode to 32 bytes");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, 16)
            );
            byte[] padded = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            byte[] plaintext = removePkcs7(padded);
            if (plaintext.length < 20) {
                throw new IllegalArgumentException("WeChat callback plaintext is too short");
            }
            int messageLength = ByteBuffer.wrap(plaintext, 16, 4).getInt();
            if (messageLength < 0 || 20L + messageLength > plaintext.length) {
                throw new IllegalArgumentException("WeChat callback message length is invalid");
            }
            byte[] message = java.util.Arrays.copyOfRange(plaintext, 20, 20 + messageLength);
            byte[] appId = java.util.Arrays.copyOfRange(plaintext, 20 + messageLength, plaintext.length);
            if (!MessageDigest.isEqual(
                    miniProgramProperties.appId().trim().getBytes(StandardCharsets.UTF_8), appId)) {
                throw new IllegalArgumentException("WeChat callback AppID mismatch");
            }
            return new String(message, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to decrypt WeChat callback", ex);
        }
    }

    private boolean validSignature(String presented, List<String> values) {
        if (!StringUtils.hasText(presented)
                || values.stream().anyMatch(value -> !StringUtils.hasText(value))) {
            return false;
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        String actual = sha1(String.join("", sorted));
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                presented.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static byte[] removePkcs7(byte[] padded) {
        if (padded == null || padded.length == 0) {
            throw new IllegalArgumentException("WeChat callback padding is invalid");
        }
        int pad = padded[padded.length - 1] & 0xff;
        if (pad < 1 || pad > 32 || pad > padded.length) {
            throw new IllegalArgumentException("WeChat callback padding is invalid");
        }
        for (int index = padded.length - pad; index < padded.length; index++) {
            if ((padded[index] & 0xff) != pad) {
                throw new IllegalArgumentException("WeChat callback padding is invalid");
            }
        }
        return java.util.Arrays.copyOf(padded, padded.length - pad);
    }

    private static String sha1(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
    }
}
