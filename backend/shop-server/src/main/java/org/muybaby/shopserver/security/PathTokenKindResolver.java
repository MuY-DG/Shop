package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PathTokenKindResolver {

    public Optional<TokenKind> resolve(String path) {
        if (("/admin/auth/login".equals(path) || "/admin/auth/refresh".equals(path))
                || "/app/auth/login".equals(path)
                || "/app/auth/refresh".equals(path)) {
            return Optional.empty();
        }
        if (matchesNamespace(path, "/wxpay") || matchesNamespace(path, "/wechat")) {
            return Optional.empty();
        }
        if (matchesNamespace(path, "/admin")) {
            return Optional.of(TokenKind.ADMIN);
        }
        if (matchesNamespace(path, "/app") && !"/app/health".equals(path)) {
            return Optional.of(TokenKind.APP);
        }
        return Optional.empty();
    }

    private boolean matchesNamespace(String path, String namespace) {
        return namespace.equals(path) || path.startsWith(namespace + "/");
    }
}
