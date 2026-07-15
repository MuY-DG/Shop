package org.muybaby.shopserver.realtime;

import org.muybaby.shopserver.auth.token.TokenKind;

import java.util.List;

public record RealtimeConnectionPrincipal(
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> permissions
) {
    public RealtimeConnectionPrincipal {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
