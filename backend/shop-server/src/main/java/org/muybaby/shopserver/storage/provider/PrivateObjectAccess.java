package org.muybaby.shopserver.storage.provider;

import java.time.Instant;

public record PrivateObjectAccess(
        Mode mode,
        String url,
        Instant expiresAt
) {

    public enum Mode {
        SIGNED_URL,
        AUTHENTICATED_BLOB
    }

    public static PrivateObjectAccess authenticatedBlob() {
        return new PrivateObjectAccess(Mode.AUTHENTICATED_BLOB, null, null);
    }

    public static PrivateObjectAccess signedUrl(String url, Instant expiresAt) {
        return new PrivateObjectAccess(Mode.SIGNED_URL, url, expiresAt);
    }
}
