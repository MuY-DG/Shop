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

import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;

@Service
public class AppUserAvatarService {

    private final AppUserService appUserService;
    private final AppUserProfileMapper profileMapper;
    private final StorageService storageService;
    private final AppUserAvatarRateLimiter rateLimiter;

    public AppUserAvatarService(
            AppUserService appUserService,
            AppUserProfileMapper profileMapper,
            StorageService storageService,
            AppUserAvatarRateLimiter rateLimiter
    ) {
        this.appUserService = appUserService;
        this.profileMapper = profileMapper;
        this.storageService = storageService;
        this.rateLimiter = rateLimiter;
    }

    public AvatarUpdateResult updateAvatar(
            AuthenticatedPrincipal principal,
            MultipartFile file
    ) {
        requireAppUser(principal);
        return withinDailyLimit(principal.subjectId(), () -> {
            StorageAssetResponse asset = storageService.uploadUserAvatar(principal, file);
            if (!StringUtils.hasText(asset.publicUrl())) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            try {
                return replaceAvatar(principal.subjectId(), asset.publicUrl());
            } catch (RuntimeException ex) {
                storageService.cleanupUnusedUserAvatar(principal.subjectId(), asset.id());
                throw ex;
            }
        });
    }

    public AvatarUpdateResult updateAvatar(
            AuthenticatedPrincipal principal,
            String avatarUrl
    ) {
        requireAppUser(principal);
        String normalizedAvatarUrl = requireWechatAvatarUrl(avatarUrl);
        return withinDailyLimit(
                principal.subjectId(),
                () -> replaceAvatar(principal.subjectId(), normalizedAvatarUrl)
        );
    }

    private AppUserProfile replaceAvatar(long userId, String avatarUrl) {
        AppUserService.AvatarReplacement replacement =
                appUserService.replaceAvatar(userId, avatarUrl);
        storageService.cleanupReplacedUserAvatar(
                userId,
                replacement.previousAvatarUrl(),
                avatarUrl
        );
        return profileMapper.from(replacement.user());
    }

    private AvatarUpdateResult withinDailyLimit(
            long userId,
            Supplier<AppUserProfile> avatarChange
    ) {
        AppUserAvatarRateLimiter.Permit permit = rateLimiter.acquire(userId);
        try {
            AppUserProfile profile = avatarChange.get();
            return new AvatarUpdateResult(profile, rateLimiter.remaining(permit));
        } catch (RuntimeException ex) {
            try {
                rateLimiter.release(permit);
            } catch (RuntimeException releaseFailure) {
                ex.addSuppressed(releaseFailure);
            }
            throw ex;
        }
    }

    private void requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        appUserService.requireEnabledUser(principal.subjectId());
    }

    private String requireWechatAvatarUrl(String avatarUrl) {
        String normalized = avatarUrl == null ? "" : avatarUrl.trim();
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(host)
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || !isWechatAvatarHost(host)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private boolean isWechatAvatarHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals("qlogo.cn")
                || normalizedHost.endsWith(".qlogo.cn")
                || normalizedHost.equals("qpic.cn")
                || normalizedHost.endsWith(".qpic.cn");
    }

    public record AvatarUpdateResult(
            AppUserProfile profile,
            int remainingChanges
    ) {
    }
}
