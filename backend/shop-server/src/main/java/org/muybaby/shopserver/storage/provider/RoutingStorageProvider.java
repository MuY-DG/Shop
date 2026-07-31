package org.muybaby.shopserver.storage.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.model.ciModel.common.ImageProcessRequest;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class RoutingStorageProvider implements StorageProvider {

    private static final String PUBLIC_CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final String PRIVATE_IMAGE_CACHE_CONTROL = "private, max-age=300";
    private static final Duration MAX_DIRECT_UPLOAD_VALIDITY = Duration.ofMinutes(15);
    private static final ObjectMapper POLICY_MAPPER = new ObjectMapper();

    private final StorageRuntimeConfigService configService;
    private final CosClientFactory cosClientFactory;
    private final CosSigningClientFactory cosSigningClientFactory;
    private final ConcurrentMap<CosKey, COSClient> cosClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<CosSigningKey, COSClient> cosSigningClients = new ConcurrentHashMap<>();

    public RoutingStorageProvider(StorageRuntimeConfigService configService) {
        this(
                configService,
                RoutingStorageProvider::createCosClient,
                RoutingStorageProvider::createCosSigningClient
        );
    }

    RoutingStorageProvider(StorageRuntimeConfigService configService, CosClientFactory cosClientFactory) {
        this(configService, cosClientFactory, RoutingStorageProvider::createCosSigningClient);
    }

    RoutingStorageProvider(
            StorageRuntimeConfigService configService,
            CosClientFactory cosClientFactory,
            CosSigningClientFactory cosSigningClientFactory
    ) {
        this.configService = configService;
        this.cosClientFactory = cosClientFactory;
        this.cosSigningClientFactory = cosSigningClientFactory;
    }

    @Override
    public StoredObject put(String objectKey, String contentType, InputStream inputStream, long sizeBytes) {
        ResolvedStorageConfig config = configService.effective();
        return put(currentLocation(config, objectKey), contentType, inputStream, sizeBytes);
    }

    @Override
    public StoredObject put(
            StorageProviderKind provider,
            String objectKey,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        requireTencentCos(provider);
        ResolvedStorageConfig config = configService.effective();
        return put(currentLocation(config, objectKey), contentType, inputStream, sizeBytes);
    }

    @Override
    public StoredObject put(
            StorageObjectLocation location,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolved = resolveLocation(location, config);
        requireCosLocation(resolved, config);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(sizeBytes);
        metadata.setContentType(contentType);
        boolean publicObject = resolved.objectKey().startsWith("public/");
        if (publicObject) {
            metadata.setCacheControl(PUBLIC_CACHE_CONTROL);
        }
        PutObjectRequest request = new PutObjectRequest(resolved.container(), resolved.objectKey(), inputStream, metadata);
        request.setCannedAcl(publicObject
                ? CannedAccessControlList.PublicRead
                : CannedAccessControlList.Private);
        cos(config, resolved.region()).putObject(request);
        return new StoredObject(resolved.objectKey(), contentType, InputStream.nullInputStream(), sizeBytes);
    }

    @Override
    public StoredObject open(String objectKey) {
        ResolvedStorageConfig config = configService.effective();
        return open(currentLocation(config, objectKey));
    }

    @Override
    public StoredObject open(StorageProviderKind provider, String objectKey) {
        requireTencentCos(provider);
        ResolvedStorageConfig config = configService.effective();
        return open(currentLocation(config, objectKey));
    }

    @Override
    public StoredObject open(StorageObjectLocation location) {
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolved = resolveLocation(location, config);
        requireCosLocation(resolved, config);
        COSObject object = cos(config, resolved.region()).getObject(resolved.container(), resolved.objectKey());
        ObjectMetadata metadata = object.getObjectMetadata();
        return new StoredObject(
                resolved.objectKey(),
                metadata.getContentType(),
                object.getObjectContent(),
                metadata.getContentLength()
        );
    }

    @Override
    public PrivateObjectAccess privateReadAccess(
            StorageObjectLocation location,
            Duration validity
    ) {
        try {
            ResolvedStorageConfig config = configService.effective();
            return privateReadAccess(location, validity, config);
        } catch (RuntimeException ex) {
            return PrivateObjectAccess.authenticatedBlob();
        }
    }

    @Override
    public Function<StorageObjectLocation, PrivateObjectAccess> privateReadAccessResolver(
            Duration validity
    ) {
        return new LazyPrivateReadAccessResolver(validity);
    }

    @Override
    public DirectUploadGrant createDirectUploadGrant(
            StorageObjectLocation location,
            String contentType,
            long exactSizeBytes,
            Duration validity
    ) {
        if (exactSizeBytes <= 0 || !StringUtils.hasText(contentType)) {
            throw new IllegalArgumentException("Direct upload metadata is incomplete");
        }
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolved = resolveLocation(location, config);
        requireCosLocation(resolved, config);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Duration normalizedValidity = validity == null || validity.isNegative() || validity.isZero()
                ? Duration.ofMinutes(10)
                : validity.compareTo(MAX_DIRECT_UPLOAD_VALIDITY) > 0
                        ? MAX_DIRECT_UPLOAD_VALIDITY
                        : validity;
        Instant expiresAt = now.plus(normalizedValidity);
        String keyTime = now.getEpochSecond() + ";" + expiresAt.getEpochSecond();
        String policy = postPolicy(
                resolved,
                config.secretId(),
                contentType,
                exactSizeBytes,
                expiresAt,
                keyTime
        );
        String encodedPolicy = Base64.getEncoder().encodeToString(
                policy.getBytes(StandardCharsets.UTF_8));
        String signKey = hmacSha1Hex(config.secretKey(), keyTime);
        String stringToSign = sha1Hex(policy);
        String signature = hmacSha1Hex(signKey, stringToSign);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", resolved.objectKey());
        fields.put("Content-Type", contentType);
        fields.put("x-cos-forbid-overwrite", "true");
        fields.put("acl", "private");
        fields.put("q-sign-algorithm", "sha1");
        fields.put("q-ak", config.secretId());
        fields.put("q-key-time", keyTime);
        fields.put("q-signature", signature);
        fields.put("policy", encodedPolicy);
        fields.put("success_action_status", "204");
        ClientEndpoint uploadEndpoint = clientEndpoint(resolved, config);
        String uploadUrl = uploadEndpoint.protocol().name()
                + "://" + uploadEndpoint.host();
        return new DirectUploadGrant(uploadUrl, Map.copyOf(fields), expiresAt);
    }

    @Override
    public DirectObjectMetadata metadata(StorageObjectLocation location) {
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolved = resolveLocation(location, config);
        requireCosLocation(resolved, config);
        ObjectMetadata metadata = cos(config, resolved.region())
                .getObjectMetadata(resolved.container(), resolved.objectKey());
        return new DirectObjectMetadata(
                metadata.getContentType(),
                metadata.getContentLength(),
                metadata.getETag()
        );
    }

    @Override
    public List<ProcessedImage> processImage(
            StorageObjectLocation source,
            List<ImageProcessOutput> outputs
    ) {
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("At least one image output is required");
        }
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolved = resolveLocation(source, config);
        requireCosLocation(resolved, config);
        PicOperations operations = new PicOperations();
        operations.setIsPicInfo(1);
        List<PicOperations.Rule> rules = new ArrayList<>();
        for (ImageProcessOutput output : outputs) {
            PicOperations.Rule rule = new PicOperations.Rule();
            rule.setBucket(resolved.container());
            /*
             * COS treats a fileId without a leading slash as relative to the
             * source object's directory. Direct-upload sources live below a
             * private staging prefix, so every persisted output must use an
             * absolute bucket key.
             */
            rule.setFileId(ciAbsoluteObjectKey(output.objectKey()));
            rule.setRule(imageRule(output));
            rules.add(rule);
        }
        operations.setRules(rules);
        ImageProcessRequest request = new ImageProcessRequest(
                resolved.container(), resolved.objectKey());
        request.setPicOperations(operations);
        CIUploadResult result = cos(config, resolved.region()).processImage(request);
        List<CIObject> objects = result == null || result.getProcessResults() == null
                ? List.of()
                : result.getProcessResults().getObjectList();
        if (objects == null || objects.size() != outputs.size()) {
            throw new IllegalStateException("COS image processing returned incomplete outputs");
        }
        if (result.getOriginalInfo() == null
                || result.getOriginalInfo().getImageInfo() == null) {
            throw new IllegalStateException("COS image processing returned no source metadata");
        }
        var sourceInfo = result.getOriginalInfo().getImageInfo();

        Map<String, CIObject> objectsByKey = new LinkedHashMap<>();
        for (CIObject object : objects) {
            if (object == null) {
                throw new IllegalStateException(
                        "COS image processing returned an empty output");
            }
            String objectKey = normalizeCiObjectKey(object.getKey());
            if (!StringUtils.hasText(objectKey)
                    || objectsByKey.put(objectKey, object) != null) {
                throw new IllegalStateException(
                        "COS image processing returned ambiguous output keys");
            }
        }
        List<ProcessedImage> processed = new ArrayList<>();
        for (ImageProcessOutput output : outputs) {
            CIObject object = objectsByKey.get(output.objectKey());
            if (object == null || object.getWidth() == null || object.getHeight() == null
                    || object.getSize() == null) {
                throw new IllegalStateException("COS image processing returned invalid metadata");
            }
            applyOutputMetadata(
                    cos(config, resolved.region()),
                    resolved.container(),
                    output.objectKey(),
                    "image/webp",
                    output.publicRead()
            );
            processed.add(new ProcessedImage(
                    output.objectKey(),
                    object.getFormat(),
                    "image/webp",
                    object.getSize(),
                    object.getWidth(),
                    object.getHeight(),
                    object.getFrameCount(),
                    object.getEtag(),
                    sourceInfo.getFormat(),
                    sourceInfo.getWidth(),
                    sourceInfo.getHeight(),
                    sourceInfo.getFrameCount()
            ));
        }
        return List.copyOf(processed);
    }

    private String ciAbsoluteObjectKey(String objectKey) {
        String normalized = normalizeCiObjectKey(objectKey);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException(
                    "COS image output object key is empty");
        }
        return "/" + normalized;
    }

    private String normalizeCiObjectKey(String objectKey) {
        String normalized = objectKey == null ? "" : objectKey.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    @Override
    public void copy(
            StorageObjectLocation source,
            StorageObjectLocation destination,
            String contentType,
            boolean publicRead
    ) {
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolvedSource = resolveLocation(source, config);
        StorageObjectLocation resolvedDestination = resolveLocation(destination, config);
        requireCosLocation(resolvedSource, config);
        requireCosLocation(resolvedDestination, config);
        CopyObjectRequest request = new CopyObjectRequest(
                resolvedSource.container(),
                resolvedSource.objectKey(),
                resolvedDestination.container(),
                resolvedDestination.objectKey()
        );
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setCacheControl(publicRead
                ? PUBLIC_CACHE_CONTROL
                : PRIVATE_IMAGE_CACHE_CONTROL);
        request.setNewObjectMetadata(metadata);
        request.setMetadataDirective("Replaced");
        request.setCannedAccessControlList(publicRead
                ? CannedAccessControlList.PublicRead
                : CannedAccessControlList.Private);
        cos(config, resolvedDestination.region()).copyObject(request);
    }

    @Override
    public void delete(String objectKey) {
        ResolvedStorageConfig config = configService.effective();
        delete(currentLocation(config, objectKey));
    }

    @Override
    public void delete(StorageProviderKind provider, String objectKey) {
        requireTencentCos(provider);
        ResolvedStorageConfig config = configService.effective();
        delete(currentLocation(config, objectKey));
    }

    @Override
    public void delete(StorageObjectLocation location) {
        ResolvedStorageConfig config = configService.effective();
        StorageObjectLocation resolved = resolveLocation(location, config);
        requireCosLocation(resolved, config);
        cos(config, resolved.region()).deleteObject(resolved.container(), resolved.objectKey());
    }

    @PreDestroy
    void shutdown() {
        Map<CosKey, COSClient> clientsToClose = Map.copyOf(cosClients);
        Map<CosSigningKey, COSClient> signingClientsToClose = Map.copyOf(cosSigningClients);
        cosClients.clear();
        cosSigningClients.clear();
        for (COSClient client : clientsToClose.values()) {
            client.shutdown();
        }
        for (COSClient client : signingClientsToClose.values()) {
            client.shutdown();
        }
    }

    private COSClient cos(ResolvedStorageConfig config, String region) {
        requireCosCredentials(config);
        CosKey key = new CosKey(region, config.secretId(), config.secretKey());
        return cosClients.computeIfAbsent(
                key,
                ignored -> cosClientFactory.create(key.region(), key.secretId(), key.secretKey())
        );
    }

    private static COSClient createCosClient(String region, String secretId, String secretKey) {
        COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        return new COSClient(credentials, new ClientConfig(new Region(region)));
    }

    private COSClient signingCos(
            ResolvedStorageConfig config,
            String region,
            ClientEndpoint endpoint
    ) {
        requireCosCredentials(config);
        CosSigningKey key = new CosSigningKey(
                region,
                config.secretId(),
                config.secretKey(),
                endpoint.protocol(),
                endpoint.host()
        );
        return cosSigningClients.computeIfAbsent(
                key,
                ignored -> cosSigningClientFactory.create(
                        key.region(),
                        key.secretId(),
                        key.secretKey(),
                        key.protocol(),
                        key.endpoint()
                )
        );
    }

    private PrivateObjectAccess privateReadAccess(
            StorageObjectLocation location,
            Duration validity,
            ResolvedStorageConfig config
    ) {
        try {
            StorageObjectLocation resolved = resolveLocation(location, config);
            requireCosLocation(resolved, config);
            ClientEndpoint endpoint = clientEndpoint(resolved, config);
            Instant expiresAt = Instant.now().plus(normalizeValidity(validity));
            COSClient client = signingCos(config, resolved.region(), endpoint);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    resolved.container(),
                    resolved.objectKey(),
                    HttpMethodName.GET
            )
                    .withExpiration(Date.from(expiresAt))
                    .withResponseHeaders(
                            new ResponseHeaderOverrides()
                                    .withCacheControl(PRIVATE_IMAGE_CACHE_CONTROL)
                    );
            request.setSignPrefixMode(false);
            String signedUrl = client.generatePresignedUrl(request).toString();
            return PrivateObjectAccess.signedUrl(signedUrl, expiresAt);
        } catch (RuntimeException ex) {
            return PrivateObjectAccess.authenticatedBlob();
        }
    }

    private static COSClient createCosSigningClient(
            String region,
            String secretId,
            String secretKey,
            HttpProtocol protocol,
            String endpoint
    ) {
        COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        clientConfig.setHttpProtocol(protocol);
        clientConfig.setEndpointBuilder(
                new UserSpecifiedEndpointBuilder(endpoint, "service.cos.myqcloud.com")
        );
        return new COSClient(credentials, clientConfig);
    }

    private ClientEndpoint clientEndpoint(
            StorageObjectLocation location,
            ResolvedStorageConfig config
    ) {
        if (location.container().equals(config.bucket())
                && location.region().equals(config.region())
                && StringUtils.hasText(config.publicBaseUrl())) {
            URI configured = URI.create(config.publicBaseUrl());
            if ("https".equalsIgnoreCase(configured.getScheme())
                    && StringUtils.hasText(configured.getHost())
                    && configured.getUserInfo() == null
                    && configured.getPort() == -1
                    && (!StringUtils.hasText(configured.getPath())
                            || "/".equals(configured.getPath()))
                    && configured.getQuery() == null
                    && configured.getFragment() == null) {
                return new ClientEndpoint(HttpProtocol.https, configured.getHost());
            }
        }
        return new ClientEndpoint(
                HttpProtocol.https,
                location.container() + ".cos." + location.region() + ".myqcloud.com"
        );
    }

    private Duration normalizeValidity(Duration validity) {
        if (validity == null || validity.isNegative() || validity.isZero()) {
            return Duration.ofMinutes(5);
        }
        return validity.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : validity;
    }

    private String imageRule(ImageProcessOutput output) {
        if (output.maxDimension() <= 0 || output.quality() < 1 || output.quality() > 100) {
            throw new IllegalArgumentException("Invalid COS image processing output");
        }
        return "imageMogr2/auto-orient/thumbnail/"
                + output.maxDimension() + "x" + output.maxDimension()
                + ">/strip/format/webp/quality/" + output.quality() + "!";
    }

    private String postPolicy(
            StorageObjectLocation location,
            String secretId,
            String contentType,
            long exactSizeBytes,
            Instant expiresAt,
            String keyTime
    ) {
        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", location.container()));
        conditions.add(Map.of("q-sign-algorithm", "sha1"));
        conditions.add(Map.of("q-ak", secretId));
        conditions.add(Map.of("q-sign-time", keyTime));
        conditions.add(List.of("eq", "$key", location.objectKey()));
        conditions.add(List.of("eq", "$Content-Type", contentType));
        conditions.add(List.of("eq", "$x-cos-forbid-overwrite", "true"));
        conditions.add(List.of("eq", "$acl", "private"));
        conditions.add(List.of("eq", "$success_action_status", "204"));
        conditions.add(List.of(
                "content-length-range", exactSizeBytes, exactSizeBytes));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", expiresAt.toString());
        policy.put("conditions", conditions);
        try {
            return POLICY_MAPPER.writeValueAsString(policy);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize COS POST policy", ex);
        }
    }

    private void applyOutputMetadata(
            COSClient client,
            String bucket,
            String objectKey,
            String contentType,
            boolean publicRead
    ) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setCacheControl(publicRead
                ? PUBLIC_CACHE_CONTROL
                : PRIVATE_IMAGE_CACHE_CONTROL);
        client.updateObjectMetaData(bucket, objectKey, metadata);
        client.setObjectAcl(
                bucket,
                objectKey,
                publicRead
                        ? CannedAccessControlList.PublicRead
                        : CannedAccessControlList.Private
        );
    }

    private String sha1Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            ).toLowerCase(java.util.Locale.ROOT);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-1 is unavailable", ex);
        }
    }

    private String hmacSha1Hex(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
            ).toLowerCase(java.util.Locale.ROOT);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", ex);
        }
    }

    private void requireCosCredentials(ResolvedStorageConfig config) {
        if (!StringUtils.hasText(config.secretId())
                || !StringUtils.hasText(config.secretKey())) {
            throw new IllegalStateException("Tencent COS credentials are not configured");
        }
    }

    private void requireCosLocation(StorageObjectLocation location, ResolvedStorageConfig config) {
        requireCosCredentials(config);
        if (!StringUtils.hasText(location.container()) || !StringUtils.hasText(location.region())) {
            throw new IllegalStateException("Tencent COS object location is incomplete");
        }
    }

    private StorageObjectLocation currentLocation(ResolvedStorageConfig config, String objectKey) {
        return new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                config.bucket(),
                config.region(),
                objectKey
        );
    }

    private StorageObjectLocation resolveLocation(StorageObjectLocation location, ResolvedStorageConfig config) {
        if (location == null || !StringUtils.hasText(location.objectKey())) {
            throw new IllegalStateException("Storage object location is incomplete");
        }
        requireTencentCos(location.provider());
        String bucket = StringUtils.hasText(location.container())
                ? location.container()
                : config.bucket();
        String region = StringUtils.hasText(location.region())
                ? location.region()
                : config.region();
        return new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                bucket,
                region,
                location.objectKey()
        );
    }

    private void requireTencentCos(StorageProviderKind provider) {
        if (provider != StorageProviderKind.TENCENT_COS) {
            throw new IllegalStateException("Only Tencent COS storage is supported");
        }
    }

    private record CosKey(String region, String secretId, String secretKey) {
    }

    private record CosSigningKey(
            String region,
            String secretId,
            String secretKey,
            HttpProtocol protocol,
            String endpoint
    ) {
    }

    private record ClientEndpoint(HttpProtocol protocol, String host) {
    }

    private final class LazyPrivateReadAccessResolver
            implements Function<StorageObjectLocation, PrivateObjectAccess> {

        private final Duration validity;
        private volatile Function<StorageObjectLocation, PrivateObjectAccess> delegate;

        private LazyPrivateReadAccessResolver(Duration validity) {
            this.validity = validity;
        }

        @Override
        public PrivateObjectAccess apply(StorageObjectLocation location) {
            Function<StorageObjectLocation, PrivateObjectAccess> resolver = delegate;
            if (resolver == null) {
                synchronized (this) {
                    resolver = delegate;
                    if (resolver == null) {
                        try {
                            ResolvedStorageConfig config = configService.effective();
                            resolver = candidate -> privateReadAccess(candidate, validity, config);
                        } catch (RuntimeException ex) {
                            resolver = ignored -> PrivateObjectAccess.authenticatedBlob();
                        }
                        delegate = resolver;
                    }
                }
            }
            return resolver.apply(location);
        }
    }

    @FunctionalInterface
    interface CosClientFactory {
        COSClient create(String region, String secretId, String secretKey);
    }

    @FunctionalInterface
    interface CosSigningClientFactory {
        COSClient create(
                String region,
                String secretId,
                String secretKey,
                HttpProtocol protocol,
                String endpoint
        );
    }
}
