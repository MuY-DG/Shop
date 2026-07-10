package org.muybaby.shopserver.auth.dto;

public record AppSessionResponse(String token, String refreshToken, long expiresIn, AppUserProfile user) {
}
