package org.muybaby.shopserver.storage.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.BucketDomainConfiguration;
import com.qcloud.cos.model.DomainRule;
import com.qcloud.cos.region.Region;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class CosCustomDomainVerifier {

    private static final Logger log = LoggerFactory.getLogger(CosCustomDomainVerifier.class);
    private static final int CONNECTION_TIMEOUT_MILLIS = 3_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 6_000;

    private final CosClientFactory cosClientFactory;

    public CosCustomDomainVerifier() {
        this(CosCustomDomainVerifier::createCosClient);
    }

    CosCustomDomainVerifier(CosClientFactory cosClientFactory) {
        this.cosClientFactory = cosClientFactory;
    }

    public void requireEnabledRestDomain(
            String domain,
            String region,
            String bucket,
            String secretId,
            String secretKey
    ) {
        if (!StringUtils.hasText(domain)
                || !StringUtils.hasText(region)
                || !StringUtils.hasText(bucket)
                || !StringUtils.hasText(secretId)
                || !StringUtils.hasText(secretKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        COSClient cosClient = null;
        try {
            cosClient = cosClientFactory.create(region, secretId, secretKey);
            BucketDomainConfiguration configuration =
                    cosClient.getBucketDomainConfiguration(bucket);
            List<DomainRule> rules = configuration == null
                    ? List.of()
                    : configuration.getDomainRules();
            boolean matched = rules != null && rules.stream()
                    .anyMatch(rule -> enabledRestDomain(rule, domain));
            if (!matched) {
                throw new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND);
            }
        } catch (CosServiceException ex) {
            if (missingDomainConfiguration(ex)) {
                throw new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND);
            }
            ErrorCode errorCode = unavailable(ex.getStatusCode())
                    ? ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE
                    : ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_FAILED;
            log.warn(
                    "COS custom domain verification failed: domain={}, bucket={}, region={}, "
                            + "status={}, errorCode={}, requestId={}",
                    domain,
                    bucket,
                    region,
                    ex.getStatusCode(),
                    ex.getErrorCode(),
                    ex.getRequestId()
            );
            throw new BusinessException(errorCode);
        } catch (CosClientException ex) {
            log.warn(
                    "COS custom domain verification unavailable: domain={}, bucket={}, "
                            + "region={}, errorCode={}",
                    domain,
                    bucket,
                    region,
                    ex.getErrorCode()
            );
            throw new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE);
        } catch (IllegalArgumentException ex) {
            log.warn(
                    "COS custom domain verification configuration is invalid: "
                            + "domain={}, bucket={}, region={}",
                    domain,
                    bucket,
                    region
            );
            throw new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_FAILED);
        } finally {
            shutdownQuietly(cosClient);
        }
    }

    public List<String> listEnabledRestDomains(
            String region,
            String bucket,
            String secretId,
            String secretKey
    ) {
        if (!StringUtils.hasText(region)
                || !StringUtils.hasText(bucket)
                || !StringUtils.hasText(secretId)
                || !StringUtils.hasText(secretKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        COSClient cosClient = null;
        try {
            cosClient = cosClientFactory.create(region, secretId, secretKey);
            BucketDomainConfiguration configuration =
                    cosClient.getBucketDomainConfiguration(bucket);
            List<DomainRule> rules = configuration == null
                    ? List.of()
                    : configuration.getDomainRules();
            if (rules == null) {
                return List.of();
            }
            return rules.stream()
                    .filter(CosCustomDomainVerifier::enabledRestDomain)
                    .map(DomainRule::getName)
                    .filter(StringUtils::hasText)
                    .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (CosServiceException ex) {
            if (missingDomainConfiguration(ex)) {
                return List.of();
            }
            log.warn(
                    "COS custom domain list failed: bucket={}, region={}, "
                            + "status={}, errorCode={}, requestId={}",
                    bucket,
                    region,
                    ex.getStatusCode(),
                    ex.getErrorCode(),
                    ex.getRequestId()
            );
            throw new BusinessException(unavailable(ex.getStatusCode())
                    ? ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE
                    : ErrorCode.STORAGE_CUSTOM_DOMAIN_LIST_FAILED);
        } catch (CosClientException ex) {
            log.warn(
                    "COS custom domain list unavailable: bucket={}, region={}, errorCode={}",
                    bucket,
                    region,
                    ex.getErrorCode()
            );
            throw new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_LIST_FAILED);
        } finally {
            shutdownQuietly(cosClient);
        }
    }

    private static COSClient createCosClient(String region, String secretId, String secretKey) {
        COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setPrintShutdownStackTrace(false);
        clientConfig.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        clientConfig.setSocketTimeout(SOCKET_TIMEOUT_MILLIS);
        clientConfig.setRequestTimeout(REQUEST_TIMEOUT_MILLIS);
        clientConfig.setRequestTimeOutEnable(true);
        clientConfig.setMaxErrorRetry(1);
        return new COSClient(credentials, clientConfig);
    }

    private static boolean enabledRestDomain(DomainRule rule, String domain) {
        return enabledRestDomain(rule) && domain.equalsIgnoreCase(rule.getName());
    }

    private static boolean enabledRestDomain(DomainRule rule) {
        return rule != null
                && StringUtils.hasText(rule.getName())
                && "ENABLED".equalsIgnoreCase(rule.getStatus())
                && "REST".equalsIgnoreCase(rule.getType());
    }

    private static boolean unavailable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static boolean missingDomainConfiguration(CosServiceException ex) {
        return ex.getStatusCode() == 404
                && "NoSuchDomainConfiguration".equalsIgnoreCase(ex.getErrorCode());
    }

    private void shutdownQuietly(COSClient cosClient) {
        if (cosClient == null) {
            return;
        }
        try {
            cosClient.shutdown();
        } catch (RuntimeException ex) {
            log.debug("Failed to shut down COS domain verification client", ex);
        }
    }

    @FunctionalInterface
    interface CosClientFactory {
        COSClient create(String region, String secretId, String secretKey);
    }
}
