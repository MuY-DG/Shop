package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StorageServiceTest {

    @Autowired
    private StorageService storageService;

    @Test
    void unsupportedExtensionIsRejectedBeforeReadingBytes() {
        TrackingMultipartFile file = TrackingMultipartFile.rejectIfRead("not-image.svg", "image/svg+xml", 512);

        assertThatThrownBy(() -> storageService.uploadAdmin(adminPrincipal(), StoragePurpose.PRODUCT_IMAGE, null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isFalse();
    }

    @Test
    void oversizedImageIsRejectedBeforeReadingBytes() {
        TrackingMultipartFile file = TrackingMultipartFile.rejectIfRead("too-large.png", "image/png", 6L * 1024 * 1024);

        assertThatThrownBy(() -> storageService.uploadAdmin(adminPrincipal(), StoragePurpose.PRODUCT_IMAGE, null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isFalse();
    }

    @Test
    void corruptedImageStillReadsBytesBeforeRejecting() {
        TrackingMultipartFile file = TrackingMultipartFile.withBytes("corrupted.png", "image/png", "broken-image".getBytes());

        assertThatThrownBy(() -> storageService.uploadAdmin(adminPrincipal(), StoragePurpose.PRODUCT_IMAGE, null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isTrue();
    }

    private AuthenticatedPrincipal adminPrincipal() {
        return new AuthenticatedPrincipal(TokenKind.ADMIN, 1L, "Super", List.of("SUPER_ADMIN"), List.of("file:upload"));
    }

    private static final class TrackingMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final String contentType;
        private final long size;
        private final byte[] bytes;
        private final boolean rejectOnRead;
        private boolean bytesRead;

        private TrackingMultipartFile(String originalFilename, String contentType, long size, byte[] bytes, boolean rejectOnRead) {
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.size = size;
            this.bytes = bytes;
            this.rejectOnRead = rejectOnRead;
        }

        static TrackingMultipartFile rejectIfRead(String originalFilename, String contentType, long size) {
            return new TrackingMultipartFile(originalFilename, contentType, size, new byte[0], true);
        }

        static TrackingMultipartFile withBytes(String originalFilename, String contentType, byte[] bytes) {
            return new TrackingMultipartFile(originalFilename, contentType, bytes.length, bytes, false);
        }

        boolean bytesRead() {
            return bytesRead;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public byte[] getBytes() throws IOException {
            bytesRead = true;
            if (rejectOnRead) {
                throw new IOException("getBytes should not be called");
            }
            return bytes;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(getBytes());
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException("Not needed in tests");
        }
    }
}
