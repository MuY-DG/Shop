package org.muybaby.shopserver.auth.dto;

public record LoginTokenResponse(String token, String refreshToken, long expiresIn) {
}
