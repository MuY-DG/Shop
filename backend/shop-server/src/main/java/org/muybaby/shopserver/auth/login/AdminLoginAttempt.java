package org.muybaby.shopserver.auth.login;

public record AdminLoginAttempt(
        String pairKey,
        String accountKey,
        String ipKey
) {
}
