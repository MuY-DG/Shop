package org.muybaby.shopserver.order.cleanup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Non-reversible identities retained after an order aggregate has been purged. */
public final class PurgedOrderIdentityDigests {

    private PurgedOrderIdentityDigests() {
    }

    public static String userIdempotency(Long userId, String idempotencyKey) {
        return sha256((userId == null ? "" : userId.toString()) + "\n" + nullToEmpty(idempotencyKey));
    }

    public static String value(String value) {
        return sha256(nullToEmpty(value));
    }

    public static String nullableValue(String value) {
        return value == null || value.isBlank() ? null : value(value.trim());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
