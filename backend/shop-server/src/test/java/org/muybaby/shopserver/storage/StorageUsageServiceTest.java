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

        assertThat(activeUsageCount()).isZero();
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

        assertThat(activeUsageCount()).isZero();
    }

    private int activeUsageCount() {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where status = 'ACTIVE'
                        """)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private long insertStorageFile(String originalFilename, String status) {
        jdbcClient.sql("""
                        insert into storage_file
                            (purpose, asset_category_id, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('PRODUCT_IMAGE', 1, 'PUBLIC', 'LOCAL', '', :objectKey, :originalFilename,
                             'image/png', 'png', 68, 'abc123', 1, 1, '', null,
                             'http://localhost:8080/files/public/test.png', :status, 'ADMIN', 1)
                        """)
                .param("objectKey", "public/test/" + status.toLowerCase() + "/" + System.nanoTime() + ".png")
                .param("originalFilename", originalFilename)
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
