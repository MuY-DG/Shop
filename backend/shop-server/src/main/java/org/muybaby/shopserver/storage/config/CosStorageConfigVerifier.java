package org.muybaby.shopserver.storage.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class CosStorageConfigVerifier {

    private static final Logger log = LoggerFactory.getLogger(CosStorageConfigVerifier.class);
    private static final int CONNECTION_TIMEOUT_MILLIS = 3_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 6_000;
    private static final byte[] PROBE_BODY = new byte[]{0};

    private final CosClientFactory cosClientFactory;

    public CosStorageConfigVerifier() {
        this(CosStorageConfigVerifier::createCosClient);
    }

    CosStorageConfigVerifier(CosClientFactory cosClientFactory) {
        this.cosClientFactory = cosClientFactory;
    }

    public List<BucketLocation> listBuckets(String secretId, String secretKey) {
        requireCredentials(secretId, secretKey);
        COSClient cosClient = null;
        try {
            cosClient = cosClientFactory.create("", secretId, secretKey);
            List<Bucket> buckets = cosClient.listBuckets();
            if (buckets == null) {
                return List.of();
            }
            return buckets.stream()
                    .filter(bucket -> bucket != null
                            && StringUtils.hasText(bucket.getName())
                            && StringUtils.hasText(bucket.getLocation()))
                    .map(bucket -> new BucketLocation(
                            bucket.getName().trim(), bucket.getLocation().trim()))
                    .sorted(Comparator.comparing(BucketLocation::bucket))
                    .toList();
        } catch (CosServiceException ex) {
            logServiceFailure("list", "", "", ex);
            throw new BusinessException(unavailable(ex.getStatusCode())
                    ? ErrorCode.STORAGE_CONFIG_VERIFICATION_UNAVAILABLE
                    : ErrorCode.STORAGE_BUCKET_LIST_FAILED);
        } catch (CosClientException ex) {
            log.warn("COS bucket list unavailable: errorCode={}", ex.getErrorCode());
            throw new BusinessException(ErrorCode.STORAGE_CONFIG_VERIFICATION_UNAVAILABLE);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.STORAGE_BUCKET_LIST_FAILED);
        } finally {
            shutdownQuietly(cosClient);
        }
    }

    public void requireWritable(ResolvedStorageConfig config) {
        if (config == null
                || !StringUtils.hasText(config.region())
                || !StringUtils.hasText(config.bucket())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        requireCredentials(config.secretId(), config.secretKey());

        COSClient cosClient = null;
        String probeKey = "private/config-check/" + UUID.randomUUID() + ".bin";
        boolean uploaded = false;
        try {
            cosClient = cosClientFactory.create(
                    config.region(), config.secretId(), config.secretKey());
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(PROBE_BODY.length);
            metadata.setContentType("application/octet-stream");
            cosClient.putObject(
                    config.bucket(),
                    probeKey,
                    new ByteArrayInputStream(PROBE_BODY),
                    metadata
            );
            uploaded = true;
            ObjectMetadata stored = cosClient.getObjectMetadata(config.bucket(), probeKey);
            if (stored == null || stored.getContentLength() != PROBE_BODY.length) {
                throw new BusinessException(ErrorCode.STORAGE_CONFIG_VERIFICATION_FAILED);
            }
            cosClient.deleteObject(config.bucket(), probeKey);
            uploaded = false;
        } catch (BusinessException ex) {
            throw ex;
        } catch (CosServiceException ex) {
            logServiceFailure("probe", config.bucket(), config.region(), ex);
            throw new BusinessException(unavailable(ex.getStatusCode())
                    ? ErrorCode.STORAGE_CONFIG_VERIFICATION_UNAVAILABLE
                    : ErrorCode.STORAGE_CONFIG_VERIFICATION_FAILED);
        } catch (CosClientException ex) {
            log.warn(
                    "COS storage probe unavailable: bucket={}, region={}, errorCode={}",
                    config.bucket(), config.region(), ex.getErrorCode());
            throw new BusinessException(ErrorCode.STORAGE_CONFIG_VERIFICATION_UNAVAILABLE);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.STORAGE_CONFIG_VERIFICATION_FAILED);
        } finally {
            if (uploaded && cosClient != null) {
                try {
                    cosClient.deleteObject(config.bucket(), probeKey);
                } catch (RuntimeException cleanupFailure) {
                    log.warn(
                            "COS storage probe cleanup failed: bucket={}, region={}, key={}",
                            config.bucket(), config.region(), probeKey);
                }
            }
            shutdownQuietly(cosClient);
        }
    }

    private void requireCredentials(String secretId, String secretKey) {
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void logServiceFailure(
            String operation,
            String bucket,
            String region,
            CosServiceException ex
    ) {
        log.warn(
                "COS storage verification failed: operation={}, bucket={}, region={}, "
                        + "status={}, errorCode={}, requestId={}",
                operation, bucket, region, ex.getStatusCode(), ex.getErrorCode(), ex.getRequestId());
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

    private static boolean unavailable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void shutdownQuietly(COSClient cosClient) {
        if (cosClient == null) {
            return;
        }
        try {
            cosClient.shutdown();
        } catch (RuntimeException ex) {
            log.debug("Failed to shut down COS storage verification client", ex);
        }
    }

    public record BucketLocation(String bucket, String region) {
    }

    @FunctionalInterface
    interface CosClientFactory {
        COSClient create(String region, String secretId, String secretKey);
    }
}
