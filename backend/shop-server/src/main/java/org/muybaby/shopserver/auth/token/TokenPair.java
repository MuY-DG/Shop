package org.muybaby.shopserver.auth.token;

public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
}
