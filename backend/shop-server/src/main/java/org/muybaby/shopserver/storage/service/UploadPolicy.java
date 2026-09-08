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

    public static final int AFTER_SALE_MAX_FILES = 4;
    public static final long AFTER_SALE_IMAGE_MAX_SIZE_BYTES = 5L * 1024 * 1024;
    public static final long AFTER_SALE_VIDEO_MAX_SIZE_BYTES = 50L * 1024 * 1024;

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
        if (profile == StorageUploadProfile.AFTER_SALE_EVIDENCE
                && sizeBytes > AFTER_SALE_IMAGE_MAX_SIZE_BYTES
                || profile == StorageUploadProfile.AFTER_SALE_EVIDENCE_VIDEO
                && sizeBytes > AFTER_SALE_VIDEO_MAX_SIZE_BYTES) {
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

    public StorageUploadProfile detectAfterSaleProfile(String originalFilename, String contentType) {
        return detectLibraryProfile(originalFilename, contentType) == StorageUploadProfile.LIBRARY_IMAGE
                ? StorageUploadProfile.AFTER_SALE_EVIDENCE
                : StorageUploadProfile.AFTER_SALE_EVIDENCE_VIDEO;
    }

    /** Reject files that only claim a video MIME type without a matching container header. */
    public void requireAfterSaleVideoHeader(String contentType, byte[] header) {
        boolean mp4 = "video/mp4".equals(normalizeContentType(contentType))
                && header.length >= 12 && header[4] == 'f' && header[5] == 't'
                && header[6] == 'y' && header[7] == 'p';
        boolean webm = "video/webm".equals(normalizeContentType(contentType))
                && header.length >= 4 && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3;
        if (!mp4 && !webm) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
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
