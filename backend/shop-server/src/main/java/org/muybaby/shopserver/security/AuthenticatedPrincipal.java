package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenKind;

import java.util.List;

public record AuthenticatedPrincipal(
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> roles,
        List<String> permissions
) {
}
