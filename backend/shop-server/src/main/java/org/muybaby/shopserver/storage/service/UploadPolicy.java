package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageProperties;
import org.muybaby.shopserver.storage.StoragePurpose;

import java.util.Locale;
import java.util.Set;

public class UploadPolicy {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> CERTIFICATE_EXTENSIONS = Set.of("pem", "crt", "cer", "txt");
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );
    private static final Set<String> CERTIFICATE_CONTENT_TYPES = Set.of(
            "text/plain",
            "application/x-x509-ca-cert",
            "application/pkix-cert"
    );

    private final StorageProperties storageProperties;

    public UploadPolicy(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    public UploadDecision requireAllowed(
            StoragePurpose purpose,
            String originalFilename,
            String contentType,
            long sizeBytes,
            boolean imageReadable
    ) {
        if (sizeBytes <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }

        String extension = extensionOf(originalFilename);
        if (purpose.image()) {
            if (!IMAGE_EXTENSIONS.contains(extension) || !IMAGE_CONTENT_TYPES.contains(normalizeContentType(contentType)) || !imageReadable) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
            if (sizeBytes > storageProperties.limits().imageMaxSize().toBytes()) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
        } else {
            if (!CERTIFICATE_EXTENSIONS.contains(extension) || !CERTIFICATE_CONTENT_TYPES.contains(normalizeContentType(contentType))) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
            if (sizeBytes > storageProperties.limits().privateFileMaxSize().toBytes()) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
        }

        FileVisibility visibility = purpose == StoragePurpose.PAYMENT_CERTIFICATE
                ? FileVisibility.PRIVATE
                : purpose.visibility();

        return new UploadDecision(purpose, visibility, extension, contentType);
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
            StoragePurpose purpose,
            FileVisibility visibility,
            String extension,
            String contentType
    ) {
    }
}
