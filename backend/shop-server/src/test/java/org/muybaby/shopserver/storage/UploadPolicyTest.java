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
                StorageProviderKind.LOCAL,
                "http://localhost:8080",
                new StorageProperties.Local("var/uploads"),
                new StorageProperties.TencentCos("", "", "", "", ""),
                new StorageProperties.Limits(
                        DataSize.ofMegabytes(5),
                        DataSize.ofMegabytes(50),
                        DataSize.ofMegabytes(1)
                )
        ));
        keyGenerator = new StorageObjectKeyGenerator();
    }

    @Test
    void requireAllowedReturnsPublicPolicyForProductImages() {
        UploadPolicy.UploadDecision decision = uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_IMAGE,
                "hotpot-cover.JPG",
                "image/jpeg",
                1024,
                true
        );

        assertThat(decision.visibility()).isEqualTo(FileVisibility.PUBLIC);
        assertThat(decision.extension()).isEqualTo("jpg");
        assertThat(decision.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void legacyTwoLimitConfigurationKeepsFiftyMegabyteVideoDefault() {
        StorageProperties.Limits limits = new StorageProperties.Limits(
                DataSize.ofMegabytes(5),
                DataSize.ofMegabytes(1)
        );

        assertThat(limits.videoMaxSize()).isEqualTo(DataSize.ofMegabytes(50));
    }

    @Test
    void publicImagePurposesAllReturnPublicVisibility() {
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "product.jpg", "image/jpeg", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_SKU_IMAGE, "sku.png", "image/png", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.CATEGORY_ICON, "category.webp", "image/webp", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.HOME_BANNER, "banner.gif", "image/gif", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.MARKETING_IMAGE, "marketing.jpeg", "image/jpeg", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.APP_ICON, "app.jpg", "image/jpeg", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.RICH_TEXT_IMAGE, "rich-text.png", "image/png", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.SPEC_VALUE_IMAGE, "spec-value.png", "image/png", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.GUARANTEE_SERVICE_ICON, "guarantee-icon.webp", "image/webp", 1024, true).visibility())
                .isEqualTo(FileVisibility.PUBLIC);
    }

    @Test
    void productVideoAllowsMatchingMp4AndWebmAsPublicFiles() {
        UploadPolicy.UploadDecision mp4 = uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.MP4",
                "video/mp4; charset=binary",
                DataSize.ofMegabytes(10).toBytes(),
                false
        );
        UploadPolicy.UploadDecision webm = uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.webm",
                "video/webm",
                DataSize.ofMegabytes(10).toBytes(),
                false
        );

        assertThat(mp4.visibility()).isEqualTo(FileVisibility.PUBLIC);
        assertThat(mp4.extension()).isEqualTo("mp4");
        assertThat(webm.visibility()).isEqualTo(FileVisibility.PUBLIC);
        assertThat(webm.extension()).isEqualTo("webm");
        assertThat(StoragePurpose.PRODUCT_VIDEO.image()).isFalse();
        assertThat(StoragePurpose.PRODUCT_VIDEO.mediaKind()).isEqualTo(StorageMediaKind.VIDEO);
    }

    @Test
    void productVideoUsesIndependentFiftyMegabyteLimit() {
        long imageLimitExceeded = DataSize.ofMegabytes(6).toBytes();
        long videoLimit = DataSize.ofMegabytes(50).toBytes();

        assertThat(uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.mp4",
                "video/mp4",
                imageLimitExceeded,
                false
        ).extension()).isEqualTo("mp4");
        assertThat(uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.webm",
                "video/webm",
                videoLimit,
                false
        ).extension()).isEqualTo("webm");

        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "too-large.mp4",
                "video/mp4",
                videoLimit + 1,
                false
        ));
    }

    @Test
    void productVideoRejectsUnsupportedAndMismatchedExtensionContentTypePairs() {
        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.mov",
                "video/quicktime",
                1024,
                false
        ));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.mp4",
                "video/webm",
                1024,
                false
        ));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.webm",
                "video/mp4",
                1024,
                false
        ));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(
                StoragePurpose.PRODUCT_VIDEO,
                "product-demo.mp4",
                "application/octet-stream",
                1024,
                false
        ));
    }

    @Test
    void privatePurposesAlwaysReturnPrivateVisibility() {
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "merchant.pem", "text/plain", 512, false).visibility())
                .isEqualTo(FileVisibility.PRIVATE);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.AFTER_SALE_IMAGE, "after-sale.png", "image/png", 1024, true).visibility())
                .isEqualTo(FileVisibility.PRIVATE);
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.REFUND_EVIDENCE, "refund.webp", "image/webp", 1024, true).visibility())
                .isEqualTo(FileVisibility.PRIVATE);
    }

    @Test
    void requireAllowedRejectsInvalidExtensionEmptyUnreadableAndOversizedFiles() {
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "not-image.svg", "image/svg+xml", 512, true));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "wrong-type.jpg", "application/octet-stream", 512, true));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "empty.jpg", "image/jpeg", 0, true));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "broken.jpg", "image/jpeg", 512, false));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "too-large.pem", "text/plain", DataSize.ofMegabytes(2).toBytes(), false));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "wrong-type.pem", "image/png", 512, false));
    }

    @Test
    void certificatePurposeAllowsExpectedExtensionsOnly() {
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "merchant.pem", "application/x-pem-file", 512, false).extension())
                .isEqualTo("pem");
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "merchant.crt", "application/x-x509-ca-cert", 512, false).extension())
                .isEqualTo("crt");
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "merchant.cer", "application/pkix-cert", 512, false).extension())
                .isEqualTo("cer");
        assertThat(uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "merchant.txt", "text/plain", 512, false).extension())
                .isEqualTo("txt");
    }

    @Test
    void generatedObjectKeyNeverContainsOriginalFilename() {
        String key = keyGenerator.nextKey(StoragePurpose.PRODUCT_IMAGE, "jpg", LocalDate.of(2026, 7, 8));

        assertThat(key).startsWith("public/product/2026/07/08/");
        assertThat(key).endsWith(".jpg");
        assertThat(key).doesNotContain("hotpot-cover");
        assertThat(key).doesNotContain(" ");
    }

    private void assertValidationFailure(Runnable executable) {
        assertThatThrownBy(executable::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
    }
}
