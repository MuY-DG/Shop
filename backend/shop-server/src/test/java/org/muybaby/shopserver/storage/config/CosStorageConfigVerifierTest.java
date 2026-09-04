package org.muybaby.shopserver.storage.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosStorageConfigVerifierTest {

    @Test
    void listsAndSortsBucketNamesWithTheirRegions() {
        COSClient cosClient = mock(COSClient.class);
        when(cosClient.listBuckets()).thenReturn(List.of(
                bucket("z-bucket-1250000000", "ap-shanghai"),
                bucket("a-bucket-1250000000", "ap-guangzhou")
        ));
        CosStorageConfigVerifier verifier = new CosStorageConfigVerifier(
                (region, secretId, secretKey) -> {
                    assertThat(region).isEmpty();
                    assertThat(secretId).isEqualTo("secret-id");
                    assertThat(secretKey).isEqualTo("secret-key");
                    return cosClient;
                });

        assertThat(verifier.listBuckets("secret-id", "secret-key"))
                .containsExactly(
                        new CosStorageConfigVerifier.BucketLocation(
                                "a-bucket-1250000000", "ap-guangzhou"),
                        new CosStorageConfigVerifier.BucketLocation(
                                "z-bucket-1250000000", "ap-shanghai")
                );
        verify(cosClient).shutdown();
    }

    @Test
    void verifiesUploadMetadataReadAndDeletionUsingOnlyTheDedicatedProbeKey() {
        COSClient cosClient = mock(COSClient.class);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(1);
        when(cosClient.getObjectMetadata(eq("shop-1250000000"), any(String.class)))
                .thenReturn(metadata);
        CosStorageConfigVerifier verifier = new CosStorageConfigVerifier(
                (region, secretId, secretKey) -> cosClient);

        verifier.requireWritable(config());

        ArgumentCaptor<String> putKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> readKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> deleteKey = ArgumentCaptor.forClass(String.class);
        verify(cosClient).putObject(
                eq("shop-1250000000"),
                putKey.capture(),
                any(InputStream.class),
                any(ObjectMetadata.class));
        verify(cosClient).getObjectMetadata(
                eq("shop-1250000000"), readKey.capture());
        verify(cosClient).deleteObject(
                eq("shop-1250000000"), deleteKey.capture());
        assertThat(putKey.getValue())
                .startsWith("private/config-check/")
                .isEqualTo(readKey.getValue())
                .isEqualTo(deleteKey.getValue());
        verify(cosClient).shutdown();
    }

    @Test
    void mapsCredentialOrPermissionFailuresToAUsableToastCode() {
        COSClient cosClient = mock(COSClient.class);
        CosServiceException forbidden = new CosServiceException("Access denied");
        forbidden.setStatusCode(403);
        forbidden.setErrorCode("AccessDenied");
        when(cosClient.listBuckets()).thenThrow(forbidden);
        CosStorageConfigVerifier verifier = new CosStorageConfigVerifier(
                (region, secretId, secretKey) -> cosClient);

        assertThatThrownBy(() -> verifier.listBuckets("secret-id", "secret-key"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.STORAGE_BUCKET_LIST_FAILED));
        verify(cosClient).shutdown();
    }

    private Bucket bucket(String name, String region) {
        Bucket bucket = new Bucket(name);
        bucket.setLocation(region);
        return bucket;
    }

    private ResolvedStorageConfig config() {
        return new ResolvedStorageConfig(
                "",
                "ap-guangzhou",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        );
    }
}
