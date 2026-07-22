package org.muybaby.shopserver.user.service;

import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.service.AppUserProfileMapper;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AppUserAvatarService {

    private final AppUserService appUserService;
    private final AppUserProfileMapper profileMapper;
    private final StorageService storageService;

    public AppUserAvatarService(
            AppUserService appUserService,
            AppUserProfileMapper profileMapper,
            StorageService storageService
    ) {
        this.appUserService = appUserService;
        this.profileMapper = profileMapper;
        this.storageService = storageService;
    }

    public AppUserProfile updateAvatar(AuthenticatedPrincipal principal, MultipartFile file) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        appUserService.requireEnabledUser(principal.subjectId());
        StorageAssetResponse asset = storageService.uploadUserAvatar(principal, file);
        if (!StringUtils.hasText(asset.publicUrl())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return profileMapper.from(appUserService.updateAvatar(principal.subjectId(), asset.publicUrl()));
    }
}
