package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageAssetScope;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageProperties;
import org.muybaby.shopserver.storage.StorageUploadProfile;

import java.util.Locale;
import java.util.Map;

public class UploadPolicy {

    private static final Map<String, String> IMAGE_CONTENT_TYPES_BY_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif",
            "svg", "image/svg+xml"
    );
    private static final Map<String, String> VIDEO_CONTENT_TYPES = Map.of(
            "mp4", "video/mp4",
            "webm", "video/webm"
    );

    private final StorageProperties storageProperties;

    public UploadPolicy(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    public UploadDecision requireAllowed(
            StorageUploadProfile profile,
            String originalFilename,
            String contentType,
            long sizeBytes,
            boolean imageReadable
    ) {
        if (profile == null || sizeBytes <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }

        String extension = extensionOf(originalFilename);
        String normalizedContentType = normalizeContentType(contentType);
        if (profile.mediaKind() == StorageMediaKind.IMAGE) {
            requireAllowedImage(extension, normalizedContentType, sizeBytes, imageReadable);
        } else if (profile.mediaKind() == StorageMediaKind.VIDEO) {
            requireAllowedVideo(extension, normalizedContentType, sizeBytes);
        } else {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }

        return new UploadDecision(
                profile,
                profile.scope(),
                profile.mediaKind(),
                profile.visibility(),
                extension,
                normalizedContentType
        );
    }

    /**
     * Detects the only two profiles accepted by the generic asset-library
     * endpoint. Private profiles must always be selected by their owning
     * business endpoint.
     */
    public StorageUploadProfile detectLibraryProfile(String originalFilename, String contentType) {
        String extension = extensionOf(originalFilename);
        String normalizedContentType = normalizeContentType(contentType);
        if (normalizedContentType.equals(IMAGE_CONTENT_TYPES_BY_EXTENSION.get(extension))) {
            return StorageUploadProfile.LIBRARY_IMAGE;
        }
        if (normalizedContentType.equals(VIDEO_CONTENT_TYPES.get(extension))) {
            return StorageUploadProfile.LIBRARY_VIDEO;
        }
        throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
    }

    public void requireAllowedImageDimensions(int width, int height) {
        StorageProperties.Limits limits = storageProperties.limits();
        if (width <= 0 || height <= 0
                || width > limits.imageMaxWidth()
                || height > limits.imageMaxHeight()
                || (long) width * height > limits.imageMaxPixels()) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    public long imageMaxSizeBytes() {
        return storageProperties.limits().imageMaxSize().toBytes();
    }

    private void requireAllowedImage(String extension, String contentType, long sizeBytes, boolean imageReadable) {
        if (!contentType.equals(IMAGE_CONTENT_TYPES_BY_EXTENSION.get(extension)) || !imageReadable) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        if (sizeBytes > storageProperties.limits().imageMaxSize().toBytes()) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private void requireAllowedVideo(String extension, String contentType, long sizeBytes) {
        if (!contentType.equals(VIDEO_CONTENT_TYPES.get(extension))) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        if (sizeBytes > storageProperties.limits().videoMaxSize().toBytes()) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private String extensionOf(String originalFilename) {
        int dotIndex = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return originalFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int separator = contentType.indexOf(';');
        String normalized = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    public record UploadDecision(
            StorageUploadProfile profile,
            StorageAssetScope scope,
            StorageMediaKind mediaKind,
            FileVisibility visibility,
            String extension,
            String contentType
    ) {
    }
}
