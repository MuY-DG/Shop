package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.service.StorageObjectKeyGenerator;
import org.muybaby.shopserver.storage.service.UploadPolicy;
import org.springframework.util.unit.DataSize;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadPolicyTest {

    private UploadPolicy uploadPolicy;
    private StorageObjectKeyGenerator keyGenerator;

    @BeforeEach
    void setUp() {
        uploadPolicy = new UploadPolicy(new StorageProperties(
                new StorageProperties.Limits(
                        DataSize.ofMegabytes(5),
                        DataSize.ofMegabytes(50)
                )
        ));
        keyGenerator = new StorageObjectKeyGenerator();
    }

    @Test
    void profilesFixScopeMediaKindAndVisibility() {
        assertThat(StorageUploadProfile.LIBRARY_IMAGE.scope()).isEqualTo(StorageAssetScope.LIBRARY);
        assertThat(StorageUploadProfile.LIBRARY_IMAGE.mediaKind()).isEqualTo(StorageMediaKind.IMAGE);
        assertThat(StorageUploadProfile.LIBRARY_IMAGE.visibility()).isEqualTo(FileVisibility.PUBLIC);

        assertThat(StorageUploadProfile.LIBRARY_VIDEO.scope()).isEqualTo(StorageAssetScope.LIBRARY);
        assertThat(StorageUploadProfile.LIBRARY_VIDEO.mediaKind()).isEqualTo(StorageMediaKind.VIDEO);
        assertThat(StorageUploadProfile.LIBRARY_VIDEO.visibility()).isEqualTo(FileVisibility.PUBLIC);

        assertThat(StorageUploadProfile.USER_AVATAR.scope()).isEqualTo(StorageAssetScope.LIBRARY);
        assertThat(StorageUploadProfile.USER_AVATAR.mediaKind()).isEqualTo(StorageMediaKind.IMAGE);
        assertThat(StorageUploadProfile.USER_AVATAR.visibility()).isEqualTo(FileVisibility.PUBLIC);

        assertThat(StorageUploadProfile.PRODUCT_REVIEW_IMAGE.scope()).isEqualTo(StorageAssetScope.LIBRARY);
        assertThat(StorageUploadProfile.PRODUCT_REVIEW_IMAGE.mediaKind()).isEqualTo(StorageMediaKind.IMAGE);
        assertThat(StorageUploadProfile.PRODUCT_REVIEW_IMAGE.visibility()).isEqualTo(FileVisibility.PUBLIC);

        assertThat(StorageUploadProfile.AFTER_SALE_EVIDENCE.scope()).isEqualTo(StorageAssetScope.ATTACHMENT);
        assertThat(StorageUploadProfile.AFTER_SALE_EVIDENCE.mediaKind()).isEqualTo(StorageMediaKind.IMAGE);
        assertThat(StorageUploadProfile.AFTER_SALE_EVIDENCE.visibility()).isEqualTo(FileVisibility.PRIVATE);
    }

    @Test
    void libraryImageProfileReturnsNormalizedPublicDecision() {
        UploadPolicy.UploadDecision decision = uploadPolicy.requireAllowed(
                StorageUploadProfile.LIBRARY_IMAGE,
                "hotpot-cover.JPG",
                "image/jpeg; charset=binary",
                1024,
                true
        );

        assertThat(decision.profile()).isEqualTo(StorageUploadProfile.LIBRARY_IMAGE);
        assertThat(decision.scope()).isEqualTo(StorageAssetScope.LIBRARY);
        assertThat(decision.mediaKind()).isEqualTo(StorageMediaKind.IMAGE);
        assertThat(decision.visibility()).isEqualTo(FileVisibility.PUBLIC);
        assertThat(decision.extension()).isEqualTo("jpg");
        assertThat(decision.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void libraryVideoProfileAllowsMatchingMp4AndWebmWithinIndependentLimit() {
        long videoLimit = DataSize.ofMegabytes(50).toBytes();

        assertThat(uploadPolicy.requireAllowed(
                StorageUploadProfile.LIBRARY_VIDEO,
                "product-demo.MP4",
                "video/mp4; charset=binary",
                DataSize.ofMegabytes(10).toBytes(),
                false
        ).extension()).isEqualTo("mp4");
        assertThat(uploadPolicy.requireAllowed(
                StorageUploadProfile.LIBRARY_VIDEO,
                "product-demo.webm",
                "video/webm",
                videoLimit,
                false
        ).extension()).isEqualTo("webm");

        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StorageUploadProfile.LIBRARY_VIDEO,
                "too-large.mp4",
                "video/mp4",
                videoLimit + 1,
                false
        ));
    }

    @Test
    void privateImageProfileAppliesItsOwnMediaPolicy() {
        UploadPolicy.UploadDecision evidence = uploadPolicy.requireAllowed(
                StorageUploadProfile.AFTER_SALE_EVIDENCE,
                "evidence.webp",
                "image/webp",
                1024,
                true
        );

        assertThat(evidence.scope()).isEqualTo(StorageAssetScope.ATTACHMENT);
        assertThat(evidence.visibility()).isEqualTo(FileVisibility.PRIVATE);
    }

    @Test
    void policyRejectsMismatchedUnreadableEmptyAndOversizedFiles() {
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StorageUploadProfile.LIBRARY_IMAGE,
                "wrong-type.jpg", "application/octet-stream", 512, true));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StorageUploadProfile.LIBRARY_IMAGE,
                "empty.jpg", "image/jpeg", 0, true));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StorageUploadProfile.LIBRARY_IMAGE,
                "broken.jpg", "image/jpeg", 512, false));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StorageUploadProfile.LIBRARY_VIDEO,
                "product-demo.mp4", "video/webm", 1024, false));
    }

    @Test
    void genericLibraryDetectionAcceptsOnlyCompatibleImagesAndVideos() {
        assertThat(uploadPolicy.detectLibraryProfile("cover.PNG", "image/png"))
                .isEqualTo(StorageUploadProfile.LIBRARY_IMAGE);
        assertThat(uploadPolicy.detectLibraryProfile("icon.SVG", "image/svg+xml; charset=utf-8"))
                .isEqualTo(StorageUploadProfile.LIBRARY_IMAGE);
        assertThat(uploadPolicy.detectLibraryProfile("demo.webm", "video/webm; charset=binary"))
                .isEqualTo(StorageUploadProfile.LIBRARY_VIDEO);

        assertValidationFailure(() -> uploadPolicy.detectLibraryProfile("merchant.pem", "text/plain"));
        assertValidationFailure(() -> uploadPolicy.detectLibraryProfile("fake.jpg", "video/mp4"));
    }

    @Test
    void svgUsesTheImageProfileAndRequiresMatchingMimeType() {
        UploadPolicy.UploadDecision decision = uploadPolicy.requireAllowed(
                StorageUploadProfile.LIBRARY_IMAGE,
                "product-icon.SVG",
                "image/svg+xml; charset=utf-8",
                1024,
                true
        );

        assertThat(decision.extension()).isEqualTo("svg");
        assertThat(decision.contentType()).isEqualTo("image/svg+xml");
        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StorageUploadProfile.LIBRARY_IMAGE,
                "product-icon.svg",
                "text/xml",
                1024,
                true
        ));
    }

    @Test
    void imageDimensionsAndDecodedPixelCountAreBoundedIndependentlyFromFileSize() {
        uploadPolicy.requireAllowedImageDimensions(4000, 4000);

        assertValidationFailure(() -> uploadPolicy.requireAllowedImageDimensions(8193, 1));
        assertValidationFailure(() -> uploadPolicy.requireAllowedImageDimensions(1, 8193));
        assertValidationFailure(() -> uploadPolicy.requireAllowedImageDimensions(5001, 5001));
        assertValidationFailure(() -> uploadPolicy.requireAllowedImageDimensions(0, 100));
    }

    @Test
    void generatedObjectKeysUseScopeAndMediaKindInsteadOfBusinessPurpose() {
        LocalDate date = LocalDate.of(2026, 7, 13);

        String libraryKey = keyGenerator.nextKey(StorageUploadProfile.LIBRARY_IMAGE, "JPG", date);
        String attachmentKey = keyGenerator.nextKey(StorageUploadProfile.AFTER_SALE_EVIDENCE, "png", date);
        String secretKey = keyGenerator.nextKey(
                StorageAssetScope.SECRET,
                StorageMediaKind.DOCUMENT,
                "pem",
                date
        );

        assertThat(libraryKey).startsWith("public/library/image/2026/07/13/").endsWith(".jpg");
        assertThat(attachmentKey).startsWith("private/attachment/image/2026/07/13/").endsWith(".png");
        assertThat(secretKey).startsWith("private/secret/document/2026/07/13/").endsWith(".pem");
    }

    private void assertValidationFailure(Runnable executable) {
        assertThatThrownBy(executable::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
    }
}
