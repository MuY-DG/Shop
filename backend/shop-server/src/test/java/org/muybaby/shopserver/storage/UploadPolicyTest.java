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
                new StorageProperties.Limits(DataSize.ofMegabytes(5), DataSize.ofMegabytes(1))
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
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "empty.jpg", "image/jpeg", 0, true));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PRODUCT_IMAGE, "broken.jpg", "image/jpeg", 512, false));
        assertValidationFailure(() -> uploadPolicy.requireAllowed(StoragePurpose.PAYMENT_CERTIFICATE, "too-large.pem", "text/plain", DataSize.ofMegabytes(2).toBytes(), false));
    }

    @Test
    void certificatePurposeAllowsExpectedExtensionsOnly() {
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
