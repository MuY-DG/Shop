package org.muybaby.shopserver.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Valid DB-only/routed identity values for tests that do not exercise payment configuration. */
public final class PaymentFixtureIdentity {

    public static final long CONFIG_ID = 9_999_001L;
    public static final String CONFIG_FINGERPRINT = "f".repeat(64);

    private PaymentFixtureIdentity() {
    }

    public static String routeToken(Object businessIdentity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(businessIdentity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
