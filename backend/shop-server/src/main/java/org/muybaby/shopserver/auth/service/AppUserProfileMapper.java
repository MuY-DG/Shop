package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.user.entity.AppUser;
import org.springframework.stereotype.Component;

@Component
public class AppUserProfileMapper {

    public AppUserProfile from(AppUser user) {
        boolean authorized = Boolean.TRUE.equals(user.phoneAuthorized());
        return new AppUserProfile(
                user.id(),
                mask(user.openid(), 4, 4),
                authorized,
                authorized ? mask(user.phoneNumber(), 3, 4) : null
        );
    }

    private String mask(String value, int preferredPrefixLength, int preferredSuffixLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 2) {
            return "****";
        }
        if (value.length() <= preferredPrefixLength + preferredSuffixLength) {
            return value.substring(0, 1) + "****" + value.substring(value.length() - 1);
        }
        return value.substring(0, preferredPrefixLength)
                + "****"
                + value.substring(value.length() - preferredSuffixLength);
    }
}
