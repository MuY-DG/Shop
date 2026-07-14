package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StorageUsageServiceTest {

    @Autowired
    private StorageUsageService storageUsageService;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("delete from storage_file_usage").update();
        jdbcClient.sql("delete from storage_file").update();
    }

    @Test
    void addProtectedUsageRejectsMissingFile() {
        assertThatThrownBy(() -> storageUsageService.addProtectedUsage(
                999999L,
                StorageFileUsageType.ORDER_ITEM_SNAPSHOT,
                StorageUsageOwnerType.ORDER_ITEM,
                9001L,
                "订单 #9001",
                "snapshot-url",
                1
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        assertThat(activeUsageCount(StorageUsageOwnerType.ORDER_ITEM, 9001L)).isZero();
    }

    @Test
    void replaceOwnerUsagesRejectsDeletedFiles() {
        long deletedFileId = insertStorageFile("deleted-image.png", "DELETED");

        assertThatThrownBy(() -> storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.PRODUCT_SPU,
                101L,
                "红汤锅底",
                List.of(new StorageUsageService.UsageAssignment(
                        deletedFileId,
                        StorageFileUsageType.PRODUCT_SPU_MAIN,
                        "snapshot-url",
                        1,
                        false
                ))
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        assertThat(activeUsageCount(StorageUsageOwnerType.PRODUCT_SPU, 101L)).isZero();
    }

    @Test
    void activePublicMediaValidationRejectsWrongKindAndPrivateFiles() {
        long imageFileId = insertStorageFile(
                "media-image.png", "ACTIVE", "PRODUCT_IMAGE", "PUBLIC", "image/png", "png");
        long videoFileId = insertStorageFile(
                "media-video.mp4", "ACTIVE", "PRODUCT_VIDEO", "PUBLIC", "video/mp4", "mp4");
        long privateFileId = insertStorageFile(
                "private-cert.pem", "ACTIVE", "PAYMENT_CERTIFICATE", "PRIVATE", "application/x-pem-file", "pem");

        storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.IMAGE);
        storageUsageService.requireActivePublicMedia(videoFileId, StorageMediaKind.VIDEO);

        assertThatThrownBy(() -> storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.VIDEO))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        assertThatThrownBy(() -> storageUsageService.requireActivePublicMedia(privateFileId, StorageMediaKind.IMAGE))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private int activeUsageCount(StorageUsageOwnerType ownerType, Long ownerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where status = 'ACTIVE'
                          and owner_type = :ownerType
                          and owner_id = :ownerId
                        """)
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private long insertStorageFile(String originalFilename, String status) {
        return insertStorageFile(
                originalFilename, status, "PRODUCT_IMAGE", "PUBLIC", "image/png", "png");
    }

    private long insertStorageFile(
            String originalFilename,
            String status,
            String purpose,
            String visibility,
            String contentType,
            String extension
    ) {
        jdbcClient.sql("""
                        insert into storage_file
                            (purpose, asset_category_id, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:purpose, 1, :visibility, 'LOCAL', '', :objectKey, :originalFilename,
                             :contentType, :extension, 68, 'abc123', 1, 1, '', null,
                             'http://localhost:8080/files/public/test.png', :status, 'ADMIN', 1)
                        """)
                .param("objectKey", "public/test/" + status.toLowerCase() + "/" + System.nanoTime() + ".png")
                .param("originalFilename", originalFilename)
                .param("purpose", purpose)
                .param("visibility", visibility)
                .param("contentType", contentType)
                .param("extension", extension)
                .param("status", status)
                .update();

        Long fileId = jdbcClient.sql("""
                        select id
                        from storage_file
                        where original_filename = :originalFilename
                        order by id desc
                        limit 1
                        """)
                .param("originalFilename", originalFilename)
                .query(Long.class)
                .single();
        assertThat(fileId).isNotNull();
        return fileId;
    }
}
