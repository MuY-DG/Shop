package org.muybaby.shopserver.storage.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ciModel.common.ImageProcessRequest;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class RoutingStorageProviderDirectUploadTest {

    @Test
    void postPolicyBindsTheOnlyAllowedObjectAndUploadMetadata() throws Exception {
        StorageRuntimeConfigService configService =
                mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(new ResolvedStorageConfig(
                "https://oss.example.test",
                "ap-guangzhou",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        ));
        RoutingStorageProvider provider =
                new RoutingStorageProvider(configService);
        DirectUploadGrant grant = provider.createDirectUploadGrant(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        "shop-1250000000",
                        "ap-guangzhou",
                        "private/direct-upload/id/source.png"
                ),
                "image/png",
                1234,
                Duration.ofMinutes(10)
        );

        JsonNode policy = new ObjectMapper().readTree(
                Base64.getDecoder().decode(grant.formData().get("policy")));
        String conditions = policy.path("conditions").toString();
        assertThat(grant.uploadUrl()).isEqualTo("https://oss.example.test");
        assertThat(grant.formData())
                .containsEntry("key", "private/direct-upload/id/source.png")
                .containsEntry("Content-Type", "image/png")
                .containsEntry("acl", "private")
                .containsEntry("x-cos-forbid-overwrite", "true")
                .containsEntry("success_action_status", "204")
                .containsKeys(
                        "q-sign-algorithm",
                        "q-ak",
                        "q-key-time",
                        "q-signature"
                );
        assertThat(conditions)
                .contains("\"bucket\":\"shop-1250000000\"")
                .contains("\"q-sign-algorithm\":\"sha1\"")
                .contains("\"q-ak\":\"secret-id\"")
                .contains("\"q-sign-time\":")
                .contains("[\"eq\",\"$key\",\"private/direct-upload/id/source.png\"]")
                .contains("[\"eq\",\"$acl\",\"private\"]")
                .contains("[\"eq\",\"$success_action_status\",\"204\"]")
                .contains("[\"content-length-range\",1234,1234]");
    }

    @Test
    void imageProcessingUsesAbsoluteBucketOutputKeys() {
        StorageRuntimeConfigService configService =
                mock(StorageRuntimeConfigService.class);
        when(configService.effective()).thenReturn(new ResolvedStorageConfig(
                "https://shop-1250000000.cos.ap-guangzhou.myqcloud.com",
                "ap-guangzhou",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        ));
        COSClient cosClient = mock(COSClient.class);
        when(cosClient.processImage(any(ImageProcessRequest.class)))
                .thenThrow(new IllegalStateException("capture-only"));
        RoutingStorageProvider provider = new RoutingStorageProvider(
                configService,
                (region, secretId, secretKey) -> cosClient
        );

        assertThatThrownBy(() -> provider.processImage(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        "shop-1250000000",
                        "ap-guangzhou",
                        "private/direct-upload/id/source.png"
                ),
                List.of(
                        new StorageProvider.ImageProcessOutput(
                                "public/library/image/main.webp",
                                1920,
                                82,
                                true
                        ),
                        new StorageProvider.ImageProcessOutput(
                                "private/customer-service/thumb.webp",
                                720,
                                76,
                                false
                        )
                )
        )).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<ImageProcessRequest> request =
                ArgumentCaptor.forClass(ImageProcessRequest.class);
        verify(cosClient).processImage(request.capture());
        assertThat(request.getValue().getPicOperations().getRules())
                .extracting(rule -> rule.getFileId())
                .containsExactly(
                        "/public/library/image/main.webp",
                        "/private/customer-service/thumb.webp"
                );
    }
}
