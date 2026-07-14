package org.muybaby.shopserver.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from storage_asset").update();
    }

    @Test
    void addProtectedUsageRejectsMissingAsset() {
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
    void replaceOwnerUsagesRejectsDeletedAssets() {
        long deletedFileId = insertStorageAsset("deleted-image.png", "DELETED");

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
    void activePublicMediaValidationRequiresLibraryPublicActiveAndMatchingKind() {
        long imageFileId = insertStorageAsset(
                "media-image.png", "ACTIVE", "LIBRARY", "IMAGE", "PUBLIC", "image/png", "png");
        long videoFileId = insertStorageAsset(
                "media-video.mp4", "ACTIVE", "LIBRARY", "VIDEO", "PUBLIC", "video/mp4", "mp4");
        long attachmentFileId = insertStorageAsset(
                "private-evidence.png", "ACTIVE", "ATTACHMENT", "IMAGE", "PRIVATE", "image/png", "png");
        long deletedImageFileId = insertStorageAsset(
                "deleted-library.png", "DELETED", "LIBRARY", "IMAGE", "PUBLIC", "image/png", "png");

        storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.IMAGE);
        storageUsageService.requireActivePublicMedia(videoFileId, StorageMediaKind.VIDEO);

        assertThatThrownBy(() -> storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.VIDEO))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        assertThatThrownBy(() -> storageUsageService.requireActivePublicMedia(attachmentFileId, StorageMediaKind.IMAGE))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        assertThatThrownBy(() -> storageUsageService.requireActivePublicMedia(deletedImageFileId, StorageMediaKind.IMAGE))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    @Test
    void replaceOwnerUsagesPersistsAndReturnsAssetId() {
        long assetId = insertStorageAsset("active-image.png", "ACTIVE");

        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.PRODUCT_SPU,
                102L,
                "清汤锅底",
                List.of(new StorageUsageService.UsageAssignment(
                        assetId,
                        StorageFileUsageType.PRODUCT_SPU_MAIN,
                        "snapshot-url",
                        1,
                        false
                ))
        );

        assertThat(storageUsageService.hasActiveUsages(assetId)).isTrue();
        assertThat(storageUsageService.usages(assetId)).singleElement().satisfies(usage -> {
            assertThat(usage.assetId()).isEqualTo(assetId);
            assertThat(usage.usageType()).isEqualTo("PRODUCT_SPU_MAIN");
            assertThat(usage.ownerType()).isEqualTo("PRODUCT_SPU");
            JsonNode json = objectMapper.valueToTree(usage);
            assertThat(json.has("assetId")).isTrue();
            assertThat(json.has("fileId")).isFalse();
        });
    }

    private int activeUsageCount(StorageUsageOwnerType ownerType, Long ownerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
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

    private long insertStorageAsset(String originalFilename, String status) {
        return insertStorageAsset(
                originalFilename, status, "LIBRARY", "IMAGE", "PUBLIC", "image/png", "png");
    }

    private long insertStorageAsset(
            String originalFilename,
            String status,
            String scope,
            String mediaKind,
            String visibility,
            String contentType,
            String extension
    ) {
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:scope, :mediaKind, null, :visibility, 'LOCAL', '', :objectKey, :originalFilename,
                             :contentType, :extension, 68, 'abc123', 1, 1, '', null,
                             'http://localhost:8080/files/public/test.png', :status, 'ADMIN', 1)
                        """)
                .param("objectKey", "public/test/" + status.toLowerCase() + "/" + System.nanoTime() + ".png")
                .param("originalFilename", originalFilename)
                .param("scope", scope)
                .param("mediaKind", mediaKind)
                .param("visibility", visibility)
                .param("contentType", contentType)
                .param("extension", extension)
                .param("status", status)
                .update();

        Long fileId = jdbcClient.sql("""
                        select id
                        from storage_asset
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
