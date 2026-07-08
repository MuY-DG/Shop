package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PrivateStorageFileServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private PrivateStorageFileService privateStorageFileService;

    @Test
    void readPrivateTextAcceptsActivePrivateFileWithAllowedPurpose() {
        Long fileId = insertStorageFile(23001L, "PAYMENT_CERTIFICATE", "PRIVATE", "ACTIVE", "private/payment/allowed.pem", "certificate-text");

        String text = privateStorageFileService.readPrivateText(fileId, Set.of(StoragePurpose.PAYMENT_CERTIFICATE));

        assertThat(text).isEqualTo("certificate-text");
    }

    @Test
    void readPrivateTextRejectsPublicDeletedAndWrongPurposeFiles() {
        Long publicFileId = insertStorageFile(23002L, "PAYMENT_CERTIFICATE", "PUBLIC", "ACTIVE", "private/payment/public.pem", "public-text");
        Long deletedFileId = insertStorageFile(23003L, "PAYMENT_CERTIFICATE", "PRIVATE", "DELETED", "private/payment/deleted.pem", "deleted-text");
        Long wrongPurposeFileId = insertStorageFile(23004L, "AFTER_SALE_IMAGE", "PRIVATE", "ACTIVE", "private/payment/wrong-purpose.txt", "wrong-purpose-text");

        assertUnavailable(publicFileId);
        assertUnavailable(deletedFileId);
        assertUnavailable(wrongPurposeFileId);
    }

    private void assertUnavailable(Long fileId) {
        assertThatThrownBy(() -> privateStorageFileService.readPrivateText(fileId, Set.of(StoragePurpose.PAYMENT_CERTIFICATE)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private Long insertStorageFile(Long id, String purpose, String visibility, String status, String objectKey, String content) {
        String uniqueObjectKey = "test/" + UUID.randomUUID() + "/" + objectKey;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(uniqueObjectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_file
                            (id, purpose, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:id, :purpose, :visibility, 'LOCAL', '', :objectKey, 'file.txt',
                             'text/plain', 'txt', :sizeBytes, '', :status, 'ADMIN', 1)
                        """)
                .param("id", id)
                .param("purpose", purpose)
                .param("visibility", visibility)
                .param("objectKey", uniqueObjectKey)
                .param("sizeBytes", bytes.length)
                .param("status", status)
                .update();
        return id;
    }
}
