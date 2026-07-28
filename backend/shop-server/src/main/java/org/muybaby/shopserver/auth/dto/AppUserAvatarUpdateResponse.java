package org.muybaby.shopserver.auth.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

public record AppUserAvatarUpdateResponse(
        @JsonStringId Long userId,
        String nickname,
        String avatarUrl,
        String openidMasked,
        boolean phoneAuthorized,
        String phoneNumberMasked,
        int remainingChanges
) {

    public static AppUserAvatarUpdateResponse from(
            AppUserProfile profile,
            int remainingChanges
    ) {
        return new AppUserAvatarUpdateResponse(
                profile.userId(),
                profile.nickname(),
                profile.avatarUrl(),
                profile.openidMasked(),
                profile.phoneAuthorized(),
                profile.phoneNumberMasked(),
                remainingChanges
        );
    }
}
