package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageFileStatus;
import org.muybaby.shopserver.storage.StoragePurpose;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

@Service
public class PrivateStorageFileService {

    private final JdbcClient jdbcClient;
    private final StorageProvider storageProvider;

    public PrivateStorageFileService(JdbcClient jdbcClient, StorageProvider storageProvider) {
        this.jdbcClient = jdbcClient;
        this.storageProvider = storageProvider;
    }

    public String readPrivateText(Long fileId, Set<StoragePurpose> allowedPurposes) {
        if (fileId == null || allowedPurposes == null || allowedPurposes.isEmpty()) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        PrivateFileRow row = jdbcClient.sql("""
                        select purpose, visibility, status, object_key
                        from storage_file
                        where id = :fileId
                        """)
                .param("fileId", fileId)
                .query(this::mapPrivateFileRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        if (!FileVisibility.PRIVATE.name().equals(row.visibility())
                || !StorageFileStatus.ACTIVE.name().equals(row.status())
                || !isAllowedPurpose(row.purpose(), allowedPurposes)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }

        try (InputStream inputStream = storageProvider.open(row.objectKey()).inputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    private boolean isAllowedPurpose(String purpose, Set<StoragePurpose> allowedPurposes) {
        try {
            return allowedPurposes.contains(StoragePurpose.valueOf(purpose));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private PrivateFileRow mapPrivateFileRow(ResultSet rs, int rowNum) throws SQLException {
        return new PrivateFileRow(
                rs.getString("purpose"),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getString("object_key")
        );
    }

    private record PrivateFileRow(String purpose, String visibility, String status, String objectKey) {
    }
}
