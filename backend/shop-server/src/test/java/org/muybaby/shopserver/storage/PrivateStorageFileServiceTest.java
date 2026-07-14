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
import java.time.LocalDateTime;
import java.util.List;
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
    void readSecretTextAcceptsActivePrivateDocumentInSecretScope() {
        Long fileId = insertStorageAsset(23001L, "SECRET", "DOCUMENT", "PRIVATE", "ACTIVE", "private/payment/allowed.pem", "certificate-text");

        String text = privateStorageFileService.readSecretText(fileId);

        assertThat(text).isEqualTo("certificate-text");
    }

    @Test
    void readSecretTextRejectsPublicDeletedWrongScopeAndNonAdminFiles() {
        Long publicFileId = insertStorageAsset(23002L, "LIBRARY", "DOCUMENT", "PUBLIC", "ACTIVE", "private/payment/public.pem", "public-text");
        Long deletedFileId = insertStorageAsset(23003L, "SECRET", "DOCUMENT", "PRIVATE", "DELETED", "private/payment/deleted.pem", "deleted-text");
        Long wrongScopeFileId = insertStorageAsset(23004L, "ATTACHMENT", "IMAGE", "PRIVATE", "ACTIVE", "private/payment/wrong-scope.txt", "wrong-scope-text");
        Long appFileId = insertStorageAsset(23005L, "SECRET", "DOCUMENT", "PRIVATE", "ACTIVE", "private/payment/app.pem", "app-text");
        jdbcClient.sql("update storage_asset set uploaded_by_type = 'APP' where id = :assetId")
                .param("assetId", appFileId)
                .update();

        assertUnavailable(publicFileId);
        assertUnavailable(deletedFileId);
        assertUnavailable(wrongScopeFileId);
        assertUnavailable(appFileId);
    }

    @Test
    void stagedSecretsAreSaveableOnlyBeforeExpiryAndUnreadableAtRuntime() {
        Long stagedFileId = insertStorageAsset(23006L, "SECRET", "DOCUMENT", "PRIVATE", "ACTIVE", "private/payment/staged.pem", "staged-text");
        jdbcClient.sql("update storage_asset set expires_at = :expiresAt where id = :assetId")
                .param("expiresAt", databaseNow().plusHours(2))
                .param("assetId", stagedFileId)
                .update();

        assertUnavailable(stagedFileId);
        privateStorageFileService.lockAndValidatePaymentSecrets(List.of(stagedFileId), List.of());

        jdbcClient.sql("update storage_asset set expires_at = :expiresAt where id = :assetId")
                .param("expiresAt", databaseNow().minusSeconds(1))
                .param("assetId", stagedFileId)
                .update();
        assertThatThrownBy(() -> privateStorageFileService.lockAndValidatePaymentSecrets(List.of(stagedFileId), List.of()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private void assertUnavailable(Long fileId) {
        assertThatThrownBy(() -> privateStorageFileService.readSecretText(fileId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private Long insertStorageAsset(Long id, String scope, String mediaKind, String visibility, String status,
                                    String objectKey, String content) {
        String uniqueObjectKey = "test/" + UUID.randomUUID() + "/" + objectKey;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(uniqueObjectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:id, :scope, :mediaKind, :visibility, 'LOCAL', '', :objectKey, 'file.txt',
                             'text/plain', 'txt', :sizeBytes, '', :status, 'ADMIN', 1)
                        """)
                .param("id", id)
                .param("scope", scope)
                .param("mediaKind", mediaKind)
                .param("visibility", visibility)
                .param("objectKey", uniqueObjectKey)
                .param("sizeBytes", bytes.length)
                .param("status", status)
                .update();
        return id;
    }
}
