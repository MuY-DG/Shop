package org.muybaby.shopserver.auth.dto;

public record AppUserProfile(
        Long userId,
        String nickname,
        String openidMasked,
        boolean phoneAuthorized,
        String phoneNumberMasked
) {
}
