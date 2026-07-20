package org.muybaby.shopserver.auth.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

public record AppUserProfile(
        @JsonStringId Long userId,
        String nickname,
        String openidMasked,
        boolean phoneAuthorized,
        String phoneNumberMasked
) {
}
