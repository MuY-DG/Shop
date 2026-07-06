package org.muybaby.shopserver.auth.token;

public enum TokenKind {
    ADMIN("adm_", "adr_", "admin"),
    APP("app_", "apr_", "app");

    private final String accessPrefix;
    private final String refreshPrefix;
    private final String namespace;

    TokenKind(String accessPrefix, String refreshPrefix, String namespace) {
        this.accessPrefix = accessPrefix;
        this.refreshPrefix = refreshPrefix;
        this.namespace = namespace;
    }

    public String accessPrefix() {
        return accessPrefix;
    }

    public String refreshPrefix() {
        return refreshPrefix;
    }

    public String namespace() {
        return namespace;
    }
}
