package org.muybaby.shopserver.storage.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.BucketDomainConfiguration;
import com.qcloud.cos.model.DomainRule;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosCustomDomainVerifierTest {

    @Test
    void acceptsOnlyTheExactEnabledRestDomainForTheSubmittedBucket() {
        COSClient cosClient = mock(COSClient.class);
        BucketDomainConfiguration configuration = new BucketDomainConfiguration();
        configuration.setDomainRules(List.of(
                rule("disabled.example.test", "ENABLED", "REST"),
                rule("oss.example.test", "DISABLED", "REST"),
                rule("oss.example.test", "ENABLED", "WEBSITE"),
                rule("OSS.EXAMPLE.TEST", "ENABLED", "REST")
        ));
        when(cosClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenReturn(configuration);
        CosCustomDomainVerifier verifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> {
                    assertThat(region).isEqualTo("ap-chengdu");
                    assertThat(secretId).isEqualTo("secret-id");
                    assertThat(secretKey).isEqualTo("secret-key");
                    return cosClient;
                }
        );

        verifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        );

        verify(cosClient).getBucketDomainConfiguration("shop-1250000000");
        verify(cosClient).shutdown();
    }

    @Test
    void rejectsDomainsThatAreNotEnabledRestOriginsForTheBucket() {
        COSClient cosClient = mock(COSClient.class);
        BucketDomainConfiguration configuration = new BucketDomainConfiguration();
        configuration.setDomainRules(List.of(
                rule("oss.example.test", "DISABLED", "REST"),
                rule("oss.example.test", "ENABLED", "WEBSITE"),
                rule("other.example.test", "ENABLED", "REST")
        ));
        when(cosClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenReturn(configuration);
        CosCustomDomainVerifier verifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> cosClient);

        assertThatThrownBy(() -> verifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND));

        verify(cosClient).shutdown();
    }

    @Test
    void treatsMissingDomainConfigurationAsNotBound() {
        COSClient nullConfigurationClient = mock(COSClient.class);
        when(nullConfigurationClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenReturn(null);
        CosCustomDomainVerifier nullConfigurationVerifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> nullConfigurationClient);

        assertThatThrownBy(() -> nullConfigurationVerifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND));
        verify(nullConfigurationClient).shutdown();

        COSClient missingConfigurationClient = mock(COSClient.class);
        CosServiceException missingConfiguration =
                new CosServiceException("No domain configuration");
        missingConfiguration.setStatusCode(404);
        missingConfiguration.setErrorCode("NoSuchDomainConfiguration");
        when(missingConfigurationClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenThrow(missingConfiguration);
        CosCustomDomainVerifier missingConfigurationVerifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> missingConfigurationClient);

        assertThatThrownBy(() -> missingConfigurationVerifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND));
        verify(missingConfigurationClient).shutdown();
    }

    @Test
    void reportsCredentialBucketRegionOrPermissionFailuresClearly() {
        COSClient cosClient = mock(COSClient.class);
        CosServiceException forbidden = new CosServiceException("Access denied");
        forbidden.setStatusCode(403);
        forbidden.setErrorCode("AccessDenied");
        forbidden.setRequestId("request-id");
        when(cosClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenThrow(forbidden);
        CosCustomDomainVerifier verifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> cosClient);

        assertThatThrownBy(() -> verifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_FAILED));

        verify(cosClient).shutdown();
    }

    @Test
    void reportsNetworkAndTencentServerFailuresAsTemporarilyUnavailable() {
        COSClient networkClient = mock(COSClient.class);
        when(networkClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenThrow(new CosClientException("timed out"));
        CosCustomDomainVerifier networkVerifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> networkClient);

        assertThatThrownBy(() -> networkVerifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE));
        verify(networkClient).shutdown();

        COSClient serverClient = mock(COSClient.class);
        CosServiceException unavailable = new CosServiceException("Unavailable");
        unavailable.setStatusCode(503);
        unavailable.setErrorCode("ServiceUnavailable");
        when(serverClient.getBucketDomainConfiguration("shop-1250000000"))
                .thenThrow(unavailable);
        CosCustomDomainVerifier serverVerifier = new CosCustomDomainVerifier(
                (region, secretId, secretKey) -> serverClient);

        assertThatThrownBy(() -> serverVerifier.requireEnabledRestDomain(
                "oss.example.test",
                "ap-chengdu",
                "shop-1250000000",
                "secret-id",
                "secret-key"
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE));
        verify(serverClient).shutdown();
    }

    private DomainRule rule(String name, String status, String type) {
        DomainRule rule = new DomainRule();
        rule.setName(name);
        rule.setStatus(status);
        rule.setType(type);
        return rule;
    }
}
