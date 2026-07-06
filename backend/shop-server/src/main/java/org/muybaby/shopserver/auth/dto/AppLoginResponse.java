package org.muybaby.shopserver.auth.dto;

public record AppLoginResponse(String token, String refreshToken, long expiresIn, AppUserSummary user) {
}
