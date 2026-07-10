package org.muybaby.shopserver.auth.token;

import org.springframework.beans.factory.annotation.Autowired;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class OpaqueTokenService {

    private static final String KEY_PREFIX = "shop:auth:";

    private final TokenStore tokenStore;
    private final TokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public OpaqueTokenService(TokenStore tokenStore, TokenProperties properties) {
        this.tokenStore = tokenStore;
        this.properties = properties;
    }

    public TokenPair issue(TokenKind kind, TokenSession session) {
        if (session.kind() != kind) {
            throw new IllegalArgumentException("Token session kind does not match requested token kind");
        }
        Duration accessTtl = accessTtl(kind);
        Duration refreshTtl = refreshTtl(kind);
        String accessToken = kind.accessPrefix() + randomTokenBody();
        String refreshToken = kind.refreshPrefix() + randomTokenBody();
        boolean saved = tokenStore.saveFamily(session.sessionId(), List.of(
                new TokenGrant(key(kind, "access", accessToken), session, accessTtl),
                new TokenGrant(key(kind, "refresh", refreshToken), session, refreshTtl)
        ));
        if (!saved) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return new TokenPair(accessToken, refreshToken, accessTtl.toSeconds());
    }

    public Optional<TokenSession> lookupAccessToken(String token, TokenKind requiredKind) {
        if (token == null || !token.startsWith(requiredKind.accessPrefix())) {
            return Optional.empty();
        }
        return tokenStore.find(key(requiredKind, "access", token))
                .filter(session -> session.kind() == requiredKind)
                .filter(session -> !tokenStore.isSessionRevoked(session.sessionId()))
                .filter(session -> !tokenStore.isGenerationRevoked(session.generationId()));
    }

    public TokenSession consumeRefreshToken(String token, TokenKind requiredKind) {
        if (token == null || !token.startsWith(requiredKind.refreshPrefix())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return tokenStore.consumeRefreshAndRevokeFamily(
                        key(requiredKind, "refresh", token),
                        revocationTtl(requiredKind)
                )
                .filter(session -> session.kind() == requiredKind)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    public void revokeSession(String sessionId, TokenKind kind) {
        if (sessionId != null && !sessionId.isBlank()) {
            tokenStore.revokeSession(sessionId, revocationTtl(kind));
        }
    }

    private Duration accessTtl(TokenKind kind) {
        return kind == TokenKind.ADMIN ? properties.adminAccessTtl() : properties.appAccessTtl();
    }

    private Duration refreshTtl(TokenKind kind) {
        return kind == TokenKind.ADMIN ? properties.adminRefreshTtl() : properties.appRefreshTtl();
    }

    private Duration revocationTtl(TokenKind kind) {
        Duration accessTtl = accessTtl(kind);
        Duration refreshTtl = refreshTtl(kind);
        return accessTtl.compareTo(refreshTtl) >= 0 ? accessTtl : refreshTtl;
    }

    private String key(TokenKind kind, String tokenType, String token) {
        return KEY_PREFIX + kind.namespace() + ":" + tokenType + ":" + sha256(token);
    }

    private String randomTokenBody() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
