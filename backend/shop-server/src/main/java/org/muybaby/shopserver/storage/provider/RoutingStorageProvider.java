package org.muybaby.shopserver.storage.provider;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public class RoutingStorageProvider implements StorageProvider {

    private static final String PUBLIC_CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final String PRIVATE_IMAGE_CACHE_CONTROL = "private, max-age=300";

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
            SigningEndpoint endpoint
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
            SigningEndpoint endpoint = signingEndpoint(resolved, config);
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

    private SigningEndpoint signingEndpoint(
            StorageObjectLocation location,
            ResolvedStorageConfig config
    ) {
        if (location.container().equals(config.bucket())
                && location.region().equals(config.region())
                && StringUtils.hasText(config.publicBaseUrl())) {
            SigningEndpoint configured = parseSigningEndpoint(config.publicBaseUrl());
            if (configured != null) {
                return configured;
            }
        }
        return new SigningEndpoint(
                HttpProtocol.https,
                location.container() + ".cos." + location.region() + ".myqcloud.com"
        );
    }

    private SigningEndpoint parseSigningEndpoint(String publicBaseUrl) {
        try {
            URI uri = new URI(publicBaseUrl);
            String path = uri.getPath();
            if (!StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || (StringUtils.hasText(path) && !"/".equals(path))
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return null;
            }
            HttpProtocol protocol = "http".equalsIgnoreCase(uri.getScheme())
                    ? HttpProtocol.http
                    : "https".equalsIgnoreCase(uri.getScheme()) ? HttpProtocol.https : null;
            if (protocol == null) {
                return null;
            }
            String host = uri.getPort() < 0
                    ? uri.getHost()
                    : uri.getHost() + ":" + uri.getPort();
            return new SigningEndpoint(protocol, host);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private Duration normalizeValidity(Duration validity) {
        if (validity == null || validity.isNegative() || validity.isZero()) {
            return Duration.ofMinutes(5);
        }
        return validity.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : validity;
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

    private record SigningEndpoint(HttpProtocol protocol, String host) {
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
