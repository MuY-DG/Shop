package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenKind;

import java.util.List;

public record AuthenticatedPrincipal(
        String sessionId,
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> roles,
        List<String> permissions
) {
    public AuthenticatedPrincipal(
            TokenKind kind,
            Long subjectId,
            String subjectName,
            List<String> roles,
            List<String> permissions
    ) {
        this(null, kind, subjectId, subjectName, roles, permissions);
    }

    public AuthenticatedPrincipal {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
